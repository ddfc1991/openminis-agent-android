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


if __name__ == "__main__":
    mode = sys.argv[1]
    for m in sys.argv[2:]:
        r = fog(m) if mode == "fog" else open_time(m)
        if mode == "fog":
            short = {k: v for k, v in r.items() if k not in ("frame_t", "frame_motion", "bucket_motion")}
            print(json.dumps(short, ensure_ascii=False))
            json.dump(r, open(m + ".json", "w"))
        else:
            print(json.dumps(r, ensure_ascii=False))
