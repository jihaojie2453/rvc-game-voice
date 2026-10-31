# Notice

Voice Studio (Android) builds on the RVC ecosystem. The upstream RVC code in `converter/rvc/` is MIT licensed —
Copyright (c) 2023 liujing04, 源文雨 and Ftps — and that notice is kept verbatim in `converter/rvc/LICENSE`,
as the MIT license requires. Upstream project:
[RVC-Project/Retrieval-based-Voice-Conversion-WebUI](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI).
The app reimplements RVC's inference pipeline around ONNX exports of those models.

## Used by the app, not stored here

| Component | Source | License |
| --- | --- | --- |
| ONNX Runtime with the QNN execution provider | github.com/microsoft/onnxruntime (Android AAR `onnxruntime-android-qnn`) | MIT |
| Qualcomm AI Engine Direct (QNN) runtime libraries | bundled in the ONNX Runtime QNN package | their own license |
| ncnn (Android GPU decoder) | github.com/Tencent/ncnn | BSD-3-Clause |
| PyTorch, Transformers, pnnx (converter only) | PyPI / download.pytorch.org (`converter/requirements.txt`) | their own licenses |

## Shared models on the Releases page (`common-v1`)

The app downloads these from the upstream project's `common-v1` release (see `CommonModels.BASE`);
`converter/rvc2phone.py --common` builds the same
files from the original weights. They are converted, not retrained: exported to ONNX, and in
`hubert.htp.onnx` each GELU (exported as an erf formula) is fused into one Gelu node for the Snapdragon NPU.

| File | Model | Weights from | License |
| --- | --- | --- | --- |
| `hubert.onnx`, `hubert.htp.onnx` | ContentVec (Qian et al., ICML 2022) on HuBERT base (fairseq) | huggingface.co/lengyue233/content-vec-best, from github.com/auspicious3000/contentvec | MIT (ContentVec, fairseq) |
| `rmvpe.onnx` | RMVPE (Wei et al., Interspeech 2023) | huggingface.co/lj1995/VoiceConversionWebUI (`rmvpe.pt`) | MIT (that repository); the original RMVPE code, github.com/Dream-High/RMVPE, is Apache-2.0 |

Voice models are not included. Use only models and voices you have the right to use.
