'use client';

import { useEffect, useRef } from 'react';
import fx from './fx.module.css';

interface NodeParticle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  r: number;
  c: string;
  a: number;
  pulse: number;
}

interface Ripple {
  x: number;
  y: number;
  r: number;
  maxR: number;
  a: number;
  c: string;
}

const PALETTE = ['#22d3ee', '#a855f7', '#10b981', '#3b82f6', '#f59e0b'];

export default function InteractiveCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let w = (canvas.width = window.innerWidth);
    let h = (canvas.height = window.innerHeight);

    const onResize = () => {
      if (!canvas) return;
      w = canvas.width = window.innerWidth;
      h = canvas.height = window.innerHeight;
    };
    window.addEventListener('resize', onResize);

    const count = Math.min(72, Math.max(36, Math.floor((w * h) / 26000)));
    const nodes: NodeParticle[] = Array.from({ length: count }, (_, i) => ({
      x: Math.random() * w,
      y: Math.random() * h,
      vx: (Math.random() - 0.5) * 0.48,
      vy: (Math.random() - 0.5) * 0.48,
      r: 1.2 + Math.random() * 1.8,
      c: PALETTE[i % PALETTE.length],
      a: 0.35 + Math.random() * 0.5,
      pulse: Math.random() * Math.PI * 2,
    }));

    const ripples: Ripple[] = [];
    const mouse = { x: w * 0.5, y: h * 0.35, tx: w * 0.5, ty: h * 0.35, active: false };

    const onMove = (e: MouseEvent) => {
      mouse.tx = e.clientX;
      mouse.ty = e.clientY;
      mouse.active = true;
      document.documentElement.style.setProperty('--gx', `${e.clientX}px`);
      document.documentElement.style.setProperty('--gy', `${e.clientY}px`);
    };

    const onClick = (e: MouseEvent) => {
      ripples.push({
        x: e.clientX,
        y: e.clientY,
        r: 4,
        maxR: 220,
        a: 0.65,
        c: PALETTE[Math.floor(Math.random() * PALETTE.length)],
      });
    };

    window.addEventListener('mousemove', onMove, { passive: true });
    window.addEventListener('click', onClick, { passive: true });

    let raf = 0;
    const render = () => {
      ctx.clearRect(0, 0, w, h);

      // Smooth cursor interpolation
      mouse.x += (mouse.tx - mouse.x) * 0.08;
      mouse.y += (mouse.ty - mouse.y) * 0.08;

      // Ambient cursor halo
      if (mouse.active) {
        const grad = ctx.createRadialGradient(mouse.x, mouse.y, 0, mouse.x, mouse.y, 340);
        grad.addColorStop(0, 'rgba(34, 211, 238, 0.09)');
        grad.addColorStop(0.45, 'rgba(168, 85, 247, 0.045)');
        grad.addColorStop(1, 'rgba(0, 0, 0, 0)');
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, w, h);
      }

      // Update & draw nodes
      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i];
        n.pulse += 0.025;
        n.x += n.vx;
        n.y += n.vy;

        if (n.x < 0) n.x = w;
        else if (n.x > w) n.x = 0;
        if (n.y < 0) n.y = h;
        else if (n.y > h) n.y = 0;

        // Gentle magnetic interaction with cursor
        if (mouse.active) {
          const dx = mouse.x - n.x;
          const dy = mouse.y - n.y;
          const dist = Math.hypot(dx, dy);
          if (dist < 210 && dist > 1) {
            const force = (1 - dist / 210) * 0.022;
            n.x += dx * force;
            n.y += dy * force;

            ctx.beginPath();
            ctx.moveTo(n.x, n.y);
            ctx.lineTo(mouse.x, mouse.y);
            ctx.strokeStyle = `rgba(34, 211, 238, ${(1 - dist / 210) * 0.24})`;
            ctx.lineWidth = 0.9;
            ctx.stroke();
          }
        }

        // Connect nearby nodes
        for (let j = i + 1; j < nodes.length; j++) {
          const m = nodes[j];
          const dx = n.x - m.x;
          const dy = n.y - m.y;
          const d2 = dx * dx + dy * dy;
          if (d2 < 17500) {
            const alpha = (1 - Math.sqrt(d2) / 132) * 0.14;
            ctx.beginPath();
            ctx.moveTo(n.x, n.y);
            ctx.lineTo(m.x, m.y);
            ctx.strokeStyle = `rgba(148, 163, 184, ${alpha})`;
            ctx.lineWidth = 0.7;
            ctx.stroke();
          }
        }

        const pr = n.r + Math.sin(n.pulse) * 0.55;
        ctx.beginPath();
        ctx.arc(n.x, n.y, Math.max(0.6, pr), 0, Math.PI * 2);
        ctx.fillStyle = n.c;
        ctx.globalAlpha = n.a;
        ctx.shadowColor = n.c;
        ctx.shadowBlur = 10;
        ctx.fill();
        ctx.shadowBlur = 0;
        ctx.globalAlpha = 1;
      }

      // Click shockwave ripples
      for (let i = ripples.length - 1; i >= 0; i--) {
        const rp = ripples[i];
        rp.r += (rp.maxR - rp.r) * 0.08 + 1.5;
        rp.a *= 0.94;
        if (rp.a < 0.02 || rp.r >= rp.maxR) {
          ripples.splice(i, 1);
          continue;
        }
        ctx.beginPath();
        ctx.arc(rp.x, rp.y, rp.r, 0, Math.PI * 2);
        ctx.strokeStyle = rp.c;
        ctx.globalAlpha = rp.a;
        ctx.lineWidth = 1.6;
        ctx.shadowColor = rp.c;
        ctx.shadowBlur = 14;
        ctx.stroke();
        ctx.shadowBlur = 0;
        ctx.globalAlpha = 1;
      }

      raf = requestAnimationFrame(render);
    };

    raf = requestAnimationFrame(render);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', onResize);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('click', onClick);
    };
  }, []);

  return <canvas ref={canvasRef} className={fx.neuralCanvas} aria-hidden="true" />;
}
