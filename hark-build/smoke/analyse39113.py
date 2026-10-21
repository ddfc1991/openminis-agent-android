"""[38.9.11.3] offline analysis of the smoke recordings.
fog <mp4...>   : sky drift — frame gaps (screenrecord only emits a frame when the screen changes: a gap = the sky stood still),
                 per-frame motion (mean |Δ| of the sky region vs the previous frame) and luminance steps (flicker).
open <mp4...>  : home → chat: time from the first changed frame (tap response) to the last frame still changing (settled).
Prints JSON lines. PYTHONPATH must give numpy."""
import sys, json, subprocess
import numpy as np

W, H = 108, 241


def frames(mp4):
    p = subprocess.run(["ffmpeg", "-v", "error", "-i", mp4, "-fps_mode", "passthrough", "-vf", f"scale={W}:{H}:flags=area,format=gray",
                        "-f", "rawvideo", "-"], capture_output=True)
    a = np.frombuffer(p.stdout, np.uint8); n = a.size // (W * H)
    return a[:n * W * H].reshape(n, H, W).astype(np.float32)


def pts(mp4):
    p = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time", "-of", "csv=p=0", mp4],
                       capture_output=True, text=True)
    return [float(x) for x in p.stdout.split() if x.strip() and x.strip() != "N/A"]


def fog(mp4, y0=0.05, y1=0.45):
    f = frames(mp4); t = np.array(pts(mp4)[:len(f)])
    n = min(len(f), len(t)); f = f[:n]; t = t[:n]
    reg = f[:, int(H * y0):int(H * y1), :]
    mot = np.abs(np.diff(reg, axis=0)).mean(axis=(1, 2))           # grey levels per frame
    lum = reg.mean(axis=(1, 2)); dl = np.abs(np.diff(lum)) / np.maximum(lum[:-1], 1) * 100
    gaps = np.diff(t)
    # per 0.5 s bucket: did the sky move? (sum of motion in the bucket)
    T = t - t[0]; buckets = []
    for b in np.arange(0, T[-1] if len(T) else 0, 0.5):
        m = (T[1:] >= b) & (T[1:] < b + 0.5)
        buckets.append(round(float(mot[m].sum()), 3))
    return dict(file=mp4.split("/")[-1], frames=int(n), dur=round(float(T[-1]), 2) if n else 0,
                fps=round(n / float(T[-1]), 1) if n and T[-1] > 0 else 0,
                max_gap_ms=int(gaps.max() * 1000) if len(gaps) else None, gaps_over_250ms=int((gaps > 0.25).sum()),
                still_buckets=int(sum(1 for x in buckets if x < 0.05)), buckets=len(buckets),
                motion_mean=round(float(mot.mean()), 4) if len(mot) else 0, motion_min_bucket=min(buckets) if buckets else 0,
                lum_step_max_pct=round(float(dl.max()), 3) if len(dl) else 0, lum_steps_over_05pct=int((dl > 0.5).sum()),
                bucket_motion=buckets, frame_t=[round(float(x), 3) for x in T], frame_motion=[round(float(x), 3) for x in mot])


def open_time(mp4, thr=0.6):
    f = frames(mp4); t = np.array(pts(mp4)[:len(f)]); n = min(len(f), len(t)); f = f[:n]; t = t[:n]
    if n < 3: return dict(file=mp4.split("/")[-1], error="too few frames", frames=int(n))
    reg = f[:, int(H * 0.06):, :]
    d0 = np.abs(reg - reg[0]).mean(axis=(1, 2)); dN = np.abs(reg - reg[-1]).mean(axis=(1, 2))
    first = next((i for i in range(1, n) if d0[i] > thr), None)
    settled = next((i for i in range(n - 1, -1, -1) if dN[i] > thr), None)
    if first is None or settled is None: return dict(file=mp4.split("/")[-1], error="no change", frames=int(n))
    settled = min(settled + 1, n - 1)
    return dict(file=mp4.split("/")[-1], frames=int(n), first_change_s=round(float(t[first] - t[0]), 3),
                open_ms=int((t[settled] - t[first]) * 1000))


def drift(mp4, y0=0.30, y1=0.80, ww=540, gap=1.5, windows=((0.0, 4.0, "idle"), (4.0, 7.0, "touch"), (9.6, 14.0, "idle2"))):
    """[38.9.11.3 f] cloud drift in device px/s (phase correlation of the sky band between frames ≥gap s apart, x shift only)
    and frame-to-frame brightness change (% of mean luminance), per window of the smoke's recording (0–4 s idle, 4–7 s touch,
    9.6–14 s idle). The band sits below the conversation card and above the input bar."""
    hh = int(ww * 2412 / 1080)
    p = subprocess.run(["ffmpeg", "-v", "error", "-i", mp4, "-fps_mode", "passthrough", "-vf", f"scale={ww}:{hh}:flags=area,format=gray",
                        "-f", "rawvideo", "-"], capture_output=True)
    a = np.frombuffer(p.stdout, np.uint8); n = a.size // (ww * hh)
    f = a[:n * ww * hh].reshape(n, hh, ww).astype(np.float32)[:, int(hh * y0):int(hh * y1), :]
    t = np.array(pts(mp4)[:n]); n = min(n, len(t)); f = f[:n]; t = t[:n] - (t[0] if n else 0)
    scale = 1080.0 / ww
    win = np.hanning(f.shape[2])[None, :] * np.hanning(f.shape[1])[:, None]
    def blur(x, k=9):
        c = np.cumsum(np.pad(x, ((0, 0), (k, k)), mode="edge"), axis=1); x = (c[:, 2 * k:] - c[:, :-2 * k]) / (2 * k)
        c = np.cumsum(np.pad(x, ((k, k), (0, 0)), mode="edge"), axis=0); return (c[2 * k:, :] - c[:-2 * k, :]) / (2 * k)
    def shift(a0, a1):
        # global x shift (px of this scale) by least squares on blurred frames (static film grain averaged out); 2 iterations
        b0, b1 = blur(a0), blur(a1); dx = 0.0
        for _ in range(3):
            xs = np.arange(b1.shape[1], dtype=np.float32)
            w1 = np.array([np.interp(xs + dx, xs, r) for r in b1])   # a1 moved back by dx
            ix = np.gradient((b0 + w1) / 2, axis=1); it = w1 - b0
            m = slice(20, -20)
            dx += -float((ix[:, m] * it[:, m]).sum() / max((ix[:, m] ** 2).sum(), 1e-6))
        return dx
    def shift_pc(a0, a1):
        A = np.fft.fft2((a0 - a0.mean()) * win); B = np.fft.fft2((a1 - a1.mean()) * win)
        R = A * np.conj(B); R /= np.maximum(np.abs(R), 1e-9)
        c = np.real(np.fft.ifft2(R)); iy, ix = np.unravel_index(np.argmax(c), c.shape)
        W = c.shape[1]
        l, r = c[iy, (ix - 1) % W], c[iy, (ix + 1) % W]; d = (l - r) / (2 * (l - 2 * c[iy, ix] + r) + 1e-9)
        x = ix + d; x = x - W if x > W / 2 else x
        return -x
    lum = f.mean(axis=(1, 2)); dl = np.abs(np.diff(lum)) / np.maximum(lum[:-1], 1) * 100
    out = dict(file=mp4.split("/")[-1], frames=int(n), lum_mean=round(float(lum.mean()), 1),
               lum_min=round(float(lum.min()), 1), lum_max=round(float(lum.max()), 1),
               lum_step_max_pct=round(float(dl.max()), 2) if len(dl) else 0, lum_steps_over_1pct=int((dl > 1).sum()))
    for t0, t1, name in windows:
        idx = [i for i in range(n) if t0 <= t[i] < t1]
        sp = []; i = 0
        while i < len(idx):
            j = next((q for q in idx if t[q] >= t[idx[i]] + gap), None)
            if j is None: break
            dt = t[j] - t[idx[i]]; sp.append(shift(f[idx[i]], f[j]) * scale / dt); i = idx.index(j)
        seg = dl[[k for k in idx if k < len(dl)]] if idx else np.array([])
        out[name] = dict(drift_px_s=round(float(np.median(sp)), 2) if sp else None, drift_samples=len(sp),
                         lum_step_max_pct=round(float(seg.max()), 2) if len(seg) else None, frames=len(idx))
    # whole recording: first frame vs each frame ≥3 s later (median px/s)
    whole = [shift(f[0], f[i]) * scale / t[i] for i in range(n) if t[i] >= 3.0]
    out["whole_drift_px_s"] = round(float(np.median(whole)), 2) if whole else None
    return out


if __name__ == "__main__":
    if sys.argv[1] == "drift":
        for m in sys.argv[2:]: print(json.dumps(drift(m), ensure_ascii=False))
        sys.exit(0)
    mode = sys.argv[1]
    for m in sys.argv[2:]:
        r = fog(m) if mode == "fog" else open_time(m)
        if mode == "fog":
            short = {k: v for k, v in r.items() if k not in ("frame_t", "frame_motion", "bucket_motion")}
            print(json.dumps(short, ensure_ascii=False))
            json.dump(r, open(m + ".json", "w"))
        else:
            print(json.dumps(r, ensure_ascii=False))
