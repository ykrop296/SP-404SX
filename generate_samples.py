#!/usr/bin/env python3
"""
Generate synthetic boom bap drum samples for SP-404SX default Bank A.
Creates 12 WAV files (44100Hz, 16-bit, stereo) in the Android raw resources directory.

Samples:
  1. kick      - Deep 808-style kick drum
  2. snare     - Punchy layered snare
  3. hihat_closed - Tight closed hi-hat
  4. hihat_open - Open hi-hat with decay
  5. clap      - Layered clap/snap
  6. rim       - Rim shot
  7. shaker    - Shaker percussion
  8. crash     - Crash cymbal
  9. tom_low   - Low tom
  10. tom_high  - High tom
  11. bass_loop - Short bass note
  12. perc      - Conga/bongo percussion hit
"""

import struct
import math
import random
import os

SAMPLE_RATE = 44100
CHANNELS = 2
BITS_PER_SAMPLE = 16


def write_wav(filename, samples, sample_rate=SAMPLE_RATE, channels=CHANNELS):
    """Write samples (list of floats -1.0 to 1.0) to a WAV file."""
    # If mono input, duplicate to stereo
    if channels == 2 and isinstance(samples[0], (int, float)):
        stereo = []
        for s in samples:
            stereo.append(s)
            stereo.append(s)
        samples = stereo
        total_samples = len(samples)
    else:
        total_samples = len(samples)

    num_frames = total_samples // channels
    byte_rate = sample_rate * channels * (BITS_PER_SAMPLE // 8)
    block_align = channels * (BITS_PER_SAMPLE // 8)
    data_size = total_samples * (BITS_PER_SAMPLE // 8)

    with open(filename, 'wb') as f:
        # RIFF header
        f.write(b'RIFF')
        f.write(struct.pack('<I', 36 + data_size))
        f.write(b'WAVE')

        # fmt chunk
        f.write(b'fmt ')
        f.write(struct.pack('<I', 16))  # chunk size
        f.write(struct.pack('<H', 1))   # PCM format
        f.write(struct.pack('<H', channels))
        f.write(struct.pack('<I', sample_rate))
        f.write(struct.pack('<I', byte_rate))
        f.write(struct.pack('<H', block_align))
        f.write(struct.pack('<H', BITS_PER_SAMPLE))

        # data chunk
        f.write(b'data')
        f.write(struct.pack('<I', data_size))

        for s in samples:
            clamped = max(-1.0, min(1.0, s))
            value = int(clamped * 32767)
            f.write(struct.pack('<h', value))


def envelope(t, attack, decay, sustain_level, release, duration):
    """ADSR envelope. t and durations in seconds."""
    if t < attack:
        return t / attack if attack > 0 else 1.0
    elif t < attack + decay:
        d = (t - attack) / decay if decay > 0 else 0
        return 1.0 - d * (1.0 - sustain_level)
    elif t < duration - release:
        return sustain_level
    elif t < duration:
        r = (duration - t) / release if release > 0 else 0
        return sustain_level * r
    return 0.0


def noise():
    """White noise sample."""
    return random.uniform(-1.0, 1.0)


def gen_kick(duration=0.5):
    """Deep 808-style kick drum with pitch sweep."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        # Pitch sweep from 150Hz down to 45Hz
        freq = 45 + 105 * math.exp(-t * 15)
        phase = 2 * math.pi * (45 * t + (105 / 15) * (1 - math.exp(-t * 15)))
        # Main sine body
        osc = math.sin(phase) * 0.9
        # Sub harmonic
        sub_phase = 2 * math.pi * 45 * t
        osc += math.sin(sub_phase) * 0.3
        # Transient click
        click = noise() * math.exp(-t * 80) * 0.3
        # Envelope
        env = math.exp(-t * 5)
        s = (osc * env + click) * 0.8
        samples.append(s)
    return samples


def gen_snare(duration=0.35):
    """Punchy boom bap snare with body and noise."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        # Body (200Hz sine with pitch drop)
        freq = 200 * math.exp(-t * 3)
        body = math.sin(2 * math.pi * freq * t) * math.exp(-t * 10)
        # Tone at 180Hz
        tone = math.sin(2 * math.pi * 180 * t) * math.exp(-t * 12) * 0.4
        # Noise burst for snare wires
        snare_noise = noise() * math.exp(-t * 15)
        # Filtered noise (band-pass approximation)
        filtered = noise() * math.exp(-t * 8) * 0.5
        # Transient
        transient = noise() * math.exp(-t * 60) * 0.6
        # Mix
        s = (body * 0.5 + tone + snare_noise * 0.6 + filtered + transient) * 0.55
        samples.append(s)
    return samples


def gen_hihat_closed(duration=0.08):
    """Tight closed hi-hat."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        # Multiple metallic tones
        s = 0
        for freq in [3200, 5800, 7400, 9200, 11000]:
            s += math.sin(2 * math.pi * freq * t + random.uniform(0, 0.1)) * 0.15
        # Noise
        s += noise() * 0.5
        # Sharp envelope
        env = math.exp(-t * 80)
        s *= env * 0.6
        samples.append(s)
    return samples


def gen_hihat_open(duration=0.4):
    """Open hi-hat with longer decay."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        s = 0
        for freq in [3200, 5800, 7400, 9200, 11000, 13500]:
            s += math.sin(2 * math.pi * freq * t + random.uniform(0, 0.05)) * 0.12
        s += noise() * 0.4
        env = math.exp(-t * 6)
        s *= env * 0.5
        samples.append(s)
    return samples


def gen_clap(duration=0.3):
    """Layered hand clap."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        s = 0
        # Multiple short bursts for clap layers
        for offset in [0, 0.008, 0.016, 0.024]:
            dt = t - offset
            if dt >= 0:
                burst = noise() * math.exp(-dt * 30)
                s += burst * 0.3
        # Main body
        body_noise = noise() * math.exp(-t * 12)
        # Band-pass filtered effect (approximate)
        s += body_noise * 0.5
        # Envelope
        if t < 0.005:
            env = t / 0.005
        else:
            env = math.exp(-(t - 0.005) * 10)
        s *= env * 0.6
        samples.append(s)
    return samples


def gen_rim(duration=0.1):
    """Rim shot - short, bright, tonal."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        # High-pitched tone
        tone = math.sin(2 * math.pi * 800 * t) * 0.6
        tone2 = math.sin(2 * math.pi * 1600 * t) * 0.3
        # Click
        click = noise() * math.exp(-t * 100) * 0.8
        # Envelope
        env = math.exp(-t * 40)
        s = (tone + tone2 + click) * env * 0.7
        samples.append(s)
    return samples


def gen_shaker(duration=0.15):
    """Shaker percussion."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        # Filtered noise
        s = noise()
        # High-pass approximation by mixing with delayed sample
        s *= 0.6
        # Add some tone
        s += math.sin(2 * math.pi * 6000 * t) * noise() * 0.2
        # Envelope - quick attack, moderate decay
        if t < 0.003:
            env = t / 0.003
        else:
            env = math.exp(-(t - 0.003) * 20)
        s *= env * 0.4
        samples.append(s)
    return samples


def gen_crash(duration=1.5):
    """Crash cymbal."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        s = 0
        # Multiple inharmonic metallic tones
        for freq in [2000, 3100, 4700, 5300, 6900, 8200, 10100, 12500]:
            phase_wobble = math.sin(2 * math.pi * 0.5 * t) * 0.002
            s += math.sin(2 * math.pi * freq * (t + phase_wobble)) * 0.08
        # Noise component
        s += noise() * 0.3
        # Initial burst
        if t < 0.01:
            s += noise() * 1.5 * (0.01 - t) / 0.01
        # Long decay envelope
        env = math.exp(-t * 2.5)
        s *= env * 0.45
        samples.append(s)
    return samples


def gen_tom_low(duration=0.4):
    """Low tom drum."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        # Pitch sweep
        freq = 80 + 60 * math.exp(-t * 10)
        body = math.sin(2 * math.pi * freq * t) * 0.8
        # Attack transient
        click = noise() * math.exp(-t * 50) * 0.4
        # Envelope
        env = math.exp(-t * 6)
        s = (body + click) * env * 0.7
        samples.append(s)
    return samples


def gen_tom_high(duration=0.3):
    """High tom drum."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        freq = 150 + 80 * math.exp(-t * 12)
        body = math.sin(2 * math.pi * freq * t) * 0.8
        click = noise() * math.exp(-t * 60) * 0.4
        env = math.exp(-t * 8)
        s = (body + click) * env * 0.7
        samples.append(s)
    return samples


def gen_bass_loop(duration=0.6):
    """Short bass note (boom bap style sub bass)."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        freq = 55  # A1
        # Saw-ish bass with harmonics
        s = math.sin(2 * math.pi * freq * t) * 0.7
        s += math.sin(2 * math.pi * freq * 2 * t) * 0.2
        s += math.sin(2 * math.pi * freq * 3 * t) * 0.1
        # Subtle saturation
        s = math.tanh(s * 1.5) * 0.7
        # Envelope
        if t < 0.01:
            env = t / 0.01
        elif t > duration - 0.1:
            env = (duration - t) / 0.1
        else:
            env = 1.0
        s *= env * 0.8
        samples.append(s)
    return samples


def gen_perc(duration=0.25):
    """Conga/bongo percussion hit."""
    samples = []
    num = int(SAMPLE_RATE * duration)
    for i in range(num):
        t = i / SAMPLE_RATE
        # Tone body
        freq = 300 + 200 * math.exp(-t * 20)
        body = math.sin(2 * math.pi * freq * t) * 0.7
        # Second partial
        body2 = math.sin(2 * math.pi * freq * 1.6 * t) * 0.3
        # Slap transient
        slap = noise() * math.exp(-t * 70) * 0.5
        # Envelope
        env = math.exp(-t * 10)
        s = (body + body2 + slap) * env * 0.6
        samples.append(s)
    return samples


def main():
    # Output directory
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           'app', 'src', 'main', 'res', 'raw')
    os.makedirs(out_dir, exist_ok=True)

    # Set random seed for reproducibility
    random.seed(42)

    generators = [
        ('kick', gen_kick),
        ('snare', gen_snare),
        ('hihat_closed', gen_hihat_closed),
        ('hihat_open', gen_hihat_open),
        ('clap', gen_clap),
        ('rim', gen_rim),
        ('shaker', gen_shaker),
        ('crash', gen_crash),
        ('tom_low', gen_tom_low),
        ('tom_high', gen_tom_high),
        ('bass_loop', gen_bass_loop),
        ('perc', gen_perc),
    ]

    for name, gen_func in generators:
        filepath = os.path.join(out_dir, f'{name}.wav')
        print(f'Generating {name}...')
        samples = gen_func()
        write_wav(filepath, samples)
        duration_ms = len(samples) / SAMPLE_RATE * 1000
        print(f'  -> {filepath} ({duration_ms:.0f}ms, {len(samples)} samples)')

    print(f'\nAll {len(generators)} samples generated in {out_dir}')


if __name__ == '__main__':
    main()
