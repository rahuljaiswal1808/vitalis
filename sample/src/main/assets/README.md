# Passive spoof model goes here

Vitalis ships **no** passive anti-spoof model (see project README / feature spec §5, §10.5).

To enable the passive TFLite tier, drop a model named `vitalis_spoof.tflite` in this folder.

Expected model contract (see `TfliteSpoofScorer`):
- input : `[1, 128, 128, 3]` float32, RGB, normalized to `[0,1]`
- output: `[1, 1]` float32 sigmoid = probability the face is REAL

Then construct the detector with `Vitalis.createWithPassiveScorer(...)`.
Do not enable this in production without an eval number you can quote.
