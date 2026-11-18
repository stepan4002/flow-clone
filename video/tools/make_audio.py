"""Generates the video's original soundtrack and sound effects (no samples, no licences).

    python tools/make_audio.py      -> public/audio/{music,whoosh,pop,tick,chime}.wav

Music: 104 BPM, Fmaj7 - G6 - Am7 - Cmaj7, warm detuned pad, plucked arpeggio, sub bass and a
soft beat that enters after the intro, swells for the finale and fades out.
"""
import os
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, lfilter

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), '..', 'public', 'audio')
rng = np.random.default_rng(7)


def note(n):  # MIDI -> Hz
    return 440.0 * 2 ** ((n - 69) / 12)


def lowpass(x, hz, order=2):
    b, a = butter(order, hz / (SR / 2), 'low')
    return lfilter(b, a, x)


def highpass(x, hz, order=2):
    b, a = butter(order, hz / (SR / 2), 'high')
    return lfilter(b, a, x)


def saw(freq, t):
    return 2 * ((freq * t) % 1.0) - 1


def env_adsr(n, a, d, s, r):
    e = np.ones(n) * s
    A, D, R = int(a * SR), int(d * SR), int(r * SR)
    A = min(A, n); e[:A] = np.linspace(0, 1, A)
    D = min(D, n - A); e[A:A + D] = np.linspace(1, s, D)
    R = min(R, n); e[n - R:] *= np.linspace(1, 0, R)
    return e


def write(name, x, peak=0.89):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    if x.ndim == 1:
        x = np.stack([x, x], axis=1)
    wavfile.write(os.path.join(OUT, name), SR, (x * 32767).astype(np.int16))


def music(seconds=53.4):  # = video length (1586 frames @ 30 fps) + a short tail
    bpm = 104
    beat = 60 / bpm
    bar = 4 * beat
    n = int(seconds * SR)
    t = np.arange(n) / SR
    L = np.zeros(n); R = np.zeros(n)
    chords = [  # Fmaj7, G6, Am7, Cmaj7 (voiced around middle C)
        [53, 57, 60, 64], [55, 59, 62, 64], [57, 60, 64, 67], [48, 55, 59, 64],
    ]
    roots = [41, 43, 45, 36]
    nbars = int(seconds / bar) + 1
    for b in range(nbars):
        start = int(b * bar * SR)
        if start >= n:
            break
        length = min(int(bar * SR) + int(0.6 * SR), n - start)
        tt = np.arange(length) / SR
        ch = chords[b % 4]
        # pad: two detuned saws per note, low-passed, slow attack
        pad = np.zeros(length)
        for m in ch:
            f = note(m + 12)
            pad += saw(f * 1.003, tt) + saw(f * 0.997, tt + 0.13)
        pad = lowpass(pad, 1400) * env_adsr(length, 0.9, 0.5, 0.8, 0.8)
        L[start:start + length] += pad * 0.10
        R[start:start + length] += np.roll(pad, 220) * 0.10
        # sub bass from bar 2
        if b >= 2:
            f = note(roots[b % 4])
            bass = np.sin(2 * np.pi * f * tt) + 0.3 * np.sin(2 * np.pi * 2 * f * tt)
            bass *= env_adsr(length, 0.02, 0.3, 0.7, 0.4)
            L[start:start + length] += bass * 0.22
            R[start:start + length] += bass * 0.22
        # plucked arpeggio (8ths) from bar 1
        if b >= 1:
            seq = ch + ch[::-1]
            for i in range(8):
                s0 = start + int(i * beat / 2 * SR)
                ln = min(int(0.45 * SR), n - s0)
                if ln <= 0:
                    continue
                tp = np.arange(ln) / SR
                f = note(seq[i] + 24)
                pl = (np.sin(2 * np.pi * f * tp) + 0.25 * np.sin(2 * np.pi * 2 * f * tp)) * np.exp(-tp * 9)
                pan = 0.5 + 0.35 * np.sin(i)
                L[s0:s0 + ln] += pl * 0.07 * (1 - pan) * 2
                R[s0:s0 + ln] += pl * 0.07 * pan * 2
        # beat from bar 3 (≈ when the demo starts), quieter in the very last bar
        if b >= 3:
            for i in range(4):
                s0 = start + int(i * beat * SR)
                ln = min(int(0.35 * SR), n - s0)
                if ln <= 0:
                    continue
                tk = np.arange(ln) / SR
                kick = np.sin(2 * np.pi * (45 + 90 * np.exp(-tk * 30)) * tk) * np.exp(-tk * 12)
                L[s0:s0 + ln] += kick * 0.28
                R[s0:s0 + ln] += kick * 0.28
                # hat on the off-beat
                h0 = s0 + int(beat / 2 * SR)
                hl = min(int(0.06 * SR), n - h0)
                if hl > 0:
                    hat = highpass(rng.standard_normal(hl), 7000) * np.exp(-np.arange(hl) / SR * 60)
                    L[h0:h0 + hl] += hat * 0.035
                    R[h0:h0 + hl] += hat * 0.045
            if b % 2 == 1:  # soft clap on beat 4 every other bar
                s0 = start + int(3 * beat * SR)
                ln = min(int(0.18 * SR), n - s0)
                if ln > 0:
                    clap = lowpass(highpass(rng.standard_normal(ln), 1200), 5000) * np.exp(-np.arange(ln) / SR * 22)
                    L[s0:s0 + ln] += clap * 0.08
                    R[s0:s0 + ln] += clap * 0.08
    # simple stereo reverb-ish tail: a few delayed, filtered copies
    for d, g in [(0.113, 0.22), (0.187, 0.16), (0.263, 0.11)]:
        k = int(d * SR)
        L[k:] += lowpass(R[:-k], 3000) * g
        R[k:] += lowpass(L[:-k], 3000) * g
    # master: swell into the finale (last 8 s), fade in/out
    master = np.ones(n)
    master *= np.clip(t / 1.5, 0, 1)
    master *= np.clip((seconds - t) / 3.0, 0, 1)
    master *= 1 + 0.18 * np.clip((t - (seconds - 12)) / 4, 0, 1)
    x = np.stack([L * master, R * master], axis=1)
    x = np.tanh(x * 1.6) / 1.6  # gentle saturation
    write('music.wav', x, peak=0.8)


def whoosh():
    ln = int(0.7 * SR)
    tt = np.arange(ln) / SR
    noise = rng.standard_normal(ln)
    # band sweep up then down
    out = np.zeros(ln)
    for i in range(0, ln, 512):
        seg = noise[i:i + 512]
        f = 400 + 3500 * np.sin(np.pi * i / ln)
        out[i:i + 512] = lowpass(seg, min(f, 18000))
    out *= np.sin(np.pi * tt / tt[-1]) ** 2
    write('whoosh.wav', out, peak=0.6)


def pop():
    ln = int(0.18 * SR)
    tt = np.arange(ln) / SR
    f = 600 + 900 * np.exp(-tt * 40)
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 28)
    write('pop.wav', x, peak=0.6)


def tick():
    ln = int(0.03 * SR)
    x = highpass(rng.standard_normal(ln), 2500) * np.exp(-np.arange(ln) / SR * 220)
    write('tick.wav', x, peak=0.35)


def chime():
    ln = int(1.2 * SR)
    tt = np.arange(ln) / SR
    x = np.zeros(ln)
    for m, delay in [(84, 0.0), (88, 0.07), (91, 0.14)]:
        k = int(delay * SR)
        f = note(m)
        x[k:] += (np.sin(2 * np.pi * f * tt[:ln - k]) + 0.2 * np.sin(2 * np.pi * 3 * f * tt[:ln - k])) * np.exp(-tt[:ln - k] * 4)
    write('chime.wav', x, peak=0.5)


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    music(); whoosh(); pop(); tick(); chime()
    print('ok', sorted(os.listdir(OUT)))
