#!/usr/bin/env python3
"""
CarbonMobile - DRL Server.

Hosts a TCP server that the Java orchestrator (DrlOrchestrator) talks
to over newline-delimited JSON.  Implements a minimal PPO agent on
top of PyTorch with these characteristics:

  * State  : 37-dim float vector (see encode_state in DrlOrchestrator.java)
  * Action : Discrete(N_edges + 1)  (N_edges edge servers + cloud)
  * Reward : sent back per task by the Java side, already augmented
              with the Lyapunov queue penalty when applicable

The server is *stateful across multiple Java connections* so that PPO
can keep learning across simulation episodes — exactly the loop we need
for a "train, then benchmark" workflow.

Protocol
--------
Each line on the socket is a JSON object with a "type" field:

  {"type":"hello", "n_edges":8, "state_dim":37}
        Java → Python: announce setup at start of episode.
        Python responds with {"type":"ack"}.

  {"type":"decide", "task_id":<int>, "state":[...37 floats...]}
        Java → Python: request action for current state.
        Python returns {"type":"action", "action":<int>}.

  {"type":"reward", "task_id":<int>, "reward":<float>,
                    "next_state":[...], "done":<bool>}
        Java → Python: post-decision feedback.
        Python responds {"type":"ack"} (after recording the transition).

  {"type":"episode_end"}
        Java → Python: signal end of one simulation run.
        Python may run a PPO update and persist a checkpoint.

  {"type":"shutdown"}
        Optional clean-shutdown signal.

Run it before any Java MainApp invocation:
    python3 scripts/carbonmobile/drl/carbon_drl_server.py --train
"""

import argparse
import json
import os
import socket
import socketserver
import threading
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Deque, List, Optional

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.optim as optim


# ---------------------------------------------------------------- networks

class ActorCritic(nn.Module):
    def __init__(self, state_dim: int, n_actions: int, hidden: int = 64):
        super().__init__()
        self.shared = nn.Sequential(
            nn.Linear(state_dim, hidden), nn.Tanh(),
            nn.Linear(hidden, hidden), nn.Tanh(),
        )
        self.actor = nn.Linear(hidden, n_actions)
        self.critic = nn.Linear(hidden, 1)

    def forward(self, x):
        h = self.shared(x)
        return self.actor(h), self.critic(h).squeeze(-1)


# ---------------------------------------------------------------- buffer

@dataclass
class Transition:
    state: np.ndarray
    action: int
    log_prob: float
    value: float
    reward: float = 0.0
    done: bool = False
    next_value: float = 0.0


# ---------------------------------------------------------------- agent

class PPOAgent:
    def __init__(self, state_dim: int, n_actions: int,
                 lr: float = 3e-4, gamma: float = 0.99,
                 gae_lambda: float = 0.95, clip_eps: float = 0.2,
                 epochs: int = 4, batch_size: int = 64,
                 update_every: int = 1024,
                 device: str = "cpu"):
        self.device = torch.device(device)
        self.net = ActorCritic(state_dim, n_actions).to(self.device)
        self.opt = optim.Adam(self.net.parameters(), lr=lr)
        self.gamma = gamma
        self.gae_lambda = gae_lambda
        self.clip_eps = clip_eps
        self.epochs = epochs
        self.batch_size = batch_size
        self.update_every = update_every
        self.n_actions = n_actions
        self.state_dim = state_dim

        self.buffer: Deque[Transition] = deque()
        # taskId → buffer index (so REWARD messages can find their transition)
        self.pending: dict = {}
        self.update_count = 0
        self.eval_mode = False

    def act(self, state: np.ndarray, task_id: int):
        s = torch.as_tensor(state, dtype=torch.float32, device=self.device).unsqueeze(0)
        with torch.no_grad():
            logits, value = self.net(s)
            probs = F.softmax(logits, dim=-1)
            if self.eval_mode:
                action = int(torch.argmax(probs, dim=-1).item())
                logp = float(torch.log(probs[0, action] + 1e-12).item())
            else:
                dist = torch.distributions.Categorical(probs=probs)
                action_t = dist.sample()
                action = int(action_t.item())
                logp = float(dist.log_prob(action_t).item())
            v = float(value.item())
        if not self.eval_mode:
            t = Transition(state=np.asarray(state, dtype=np.float32),
                           action=action, log_prob=logp, value=v)
            self.buffer.append(t)
            self.pending[task_id] = len(self.buffer) - 1
        return action

    def record_reward(self, task_id: int, reward: float,
                      next_state: Optional[List[float]], done: bool):
        if self.eval_mode:
            return
        idx = self.pending.pop(task_id, None)
        if idx is None or idx >= len(self.buffer):
            return
        t = self.buffer[idx]
        t.reward = reward
        t.done = bool(done)
        if not done and next_state is not None:
            ns = torch.as_tensor(next_state, dtype=torch.float32,
                                 device=self.device).unsqueeze(0)
            with torch.no_grad():
                _, v = self.net(ns)
            t.next_value = float(v.item())
        else:
            t.next_value = 0.0

        if len(self.buffer) >= self.update_every:
            self.update()

    def update(self):
        # Filter out transitions whose reward hasn't arrived yet.
        ready = [t for t in self.buffer if t.next_value is not None]
        if len(ready) < 32:
            return
        states = torch.tensor(np.stack([t.state for t in ready]),
                              dtype=torch.float32, device=self.device)
        actions = torch.tensor([t.action for t in ready],
                               dtype=torch.long, device=self.device)
        old_log = torch.tensor([t.log_prob for t in ready],
                               dtype=torch.float32, device=self.device)
        values = torch.tensor([t.value for t in ready],
                              dtype=torch.float32, device=self.device)
        rewards = torch.tensor([t.reward for t in ready],
                               dtype=torch.float32, device=self.device)
        dones = torch.tensor([float(t.done) for t in ready],
                             dtype=torch.float32, device=self.device)
        next_v = torch.tensor([t.next_value for t in ready],
                              dtype=torch.float32, device=self.device)

        # GAE
        advantages = torch.zeros_like(rewards)
        gae = 0.0
        for i in reversed(range(len(ready))):
            delta = rewards[i] + self.gamma * next_v[i] * (1 - dones[i]) - values[i]
            gae = delta + self.gamma * self.gae_lambda * (1 - dones[i]) * gae
            advantages[i] = gae
        returns = advantages + values
        advantages = (advantages - advantages.mean()) / (advantages.std() + 1e-8)

        # PPO update
        n = len(ready)
        idxs = np.arange(n)
        for _ in range(self.epochs):
            np.random.shuffle(idxs)
            for start in range(0, n, self.batch_size):
                mb = idxs[start:start + self.batch_size]
                b_s = states[mb]; b_a = actions[mb]
                b_old = old_log[mb]; b_adv = advantages[mb]
                b_ret = returns[mb]
                logits, v = self.net(b_s)
                logp = F.log_softmax(logits, dim=-1).gather(1, b_a.unsqueeze(-1)).squeeze(-1)
                ratio = torch.exp(logp - b_old)
                surr1 = ratio * b_adv
                surr2 = torch.clamp(ratio, 1 - self.clip_eps, 1 + self.clip_eps) * b_adv
                policy_loss = -torch.min(surr1, surr2).mean()
                value_loss = F.mse_loss(v, b_ret)
                entropy = -(F.softmax(logits, dim=-1)
                              * F.log_softmax(logits, dim=-1)).sum(-1).mean()
                loss = policy_loss + 0.5 * value_loss - 0.01 * entropy
                self.opt.zero_grad()
                loss.backward()
                nn.utils.clip_grad_norm_(self.net.parameters(), 0.5)
                self.opt.step()
        self.update_count += 1
        # Drop old samples to bound memory
        self.buffer.clear()
        self.pending.clear()
        print(f"[PPO] update #{self.update_count} done on {n} samples "
              f"(avg reward {rewards.mean().item():.4f})")

    def save(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        torch.save({
            "model": self.net.state_dict(),
            "opt": self.opt.state_dict(),
            "update_count": self.update_count,
        }, path)

    def load(self, path: Path):
        if not path.exists():
            print(f"[PPO] checkpoint {path} not found — starting fresh.")
            return
        ckpt = torch.load(path, map_location=self.device, weights_only=False)
        self.net.load_state_dict(ckpt["model"])
        self.opt.load_state_dict(ckpt["opt"])
        self.update_count = ckpt.get("update_count", 0)
        print(f"[PPO] loaded checkpoint from {path} (updates={self.update_count})")


# ---------------------------------------------------------------- TCP server

agent: Optional[PPOAgent] = None
agent_lock = threading.Lock()
checkpoint_path: Optional[Path] = None
episode_count = 0


class DrlHandler(socketserver.StreamRequestHandler):
    def handle(self):
        global episode_count
        peer = self.client_address
        print(f"[server] connection from {peer}")
        for line in self.rfile:
            try:
                msg = json.loads(line.decode("utf-8").strip())
            except Exception as e:
                print(f"[server] bad JSON: {e}")
                continue
            t = msg.get("type")
            if t == "hello":
                with agent_lock:
                    if agent is None:
                        n_actions = msg["n_edges"] + 1
                        sd = msg["state_dim"]
                        globals()["agent"] = PPOAgent(state_dim=sd, n_actions=n_actions)
                        if checkpoint_path is not None:
                            agent.load(checkpoint_path)
                        if os.environ.get("CARBONMOBILE_DRL_EVAL") == "1":
                            agent.eval_mode = True
                            print("[PPO] eval mode (deterministic, no learning)")
                self._send({"type": "ack"})
            elif t == "decide":
                with agent_lock:
                    a = agent.act(np.asarray(msg["state"], dtype=np.float32),
                                  task_id=int(msg["task_id"]))
                self._send({"type": "action", "action": a})
            elif t == "reward":
                with agent_lock:
                    agent.record_reward(
                        task_id=int(msg["task_id"]),
                        reward=float(msg["reward"]),
                        next_state=msg.get("next_state"),
                        done=bool(msg.get("done", True)),
                    )
                self._send({"type": "ack"})
            elif t == "episode_end":
                episode_count += 1
                with agent_lock:
                    if not agent.eval_mode:
                        agent.update()  # drain remaining
                        if checkpoint_path is not None:
                            agent.save(checkpoint_path)
                            print(f"[server] saved checkpoint after episode {episode_count}")
                self._send({"type": "ack"})
            elif t == "shutdown":
                print("[server] shutdown requested")
                self._send({"type": "ack"})
                break
            else:
                self._send({"type": "error", "msg": f"unknown type {t}"})

    def _send(self, obj):
        try:
            self.wfile.write((json.dumps(obj) + "\n").encode("utf-8"))
            self.wfile.flush()
        except Exception:
            pass


class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    global checkpoint_path
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9999)
    p.add_argument("--checkpoint", default="scripts/carbonmobile/drl/checkpoints/ppo.pt")
    p.add_argument("--eval", action="store_true",
                   help="serve trained policy without learning")
    args = p.parse_args()

    checkpoint_path = Path(args.checkpoint)

    # If --eval, the agent will be created on first hello and immediately
    # set to eval mode after loading.
    if args.eval:
        # We need agent created at first hello — defer until then.
        os.environ["CARBONMOBILE_DRL_EVAL"] = "1"

    print(f"[server] listening on {args.host}:{args.port}")
    print(f"[server] checkpoint: {checkpoint_path} (eval={args.eval})")
    with ThreadedTCPServer((args.host, args.port), DrlHandler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\n[server] interrupted, exiting")


if __name__ == "__main__":
    main()
