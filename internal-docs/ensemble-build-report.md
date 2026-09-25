## Model build parity

40 dataset images, app normalization. A logit-gap difference below ~0.05 is negligible after calibration (slopes are ~0.2).

| Check | Max \|gap diff\| | Mean | OK |
|---|---|---|---|
| commfor-224: PyTorch vs ONNX export, same input | 0.0001 | 0.0000 | yes |
| commfor-224: authors' preprocessing + PyTorch vs app preprocessing + ONNX | 0.0001 | 0.0000 | yes |
| bundled: fp32 vs fp16 weights | 0.0222 | 0.0066 | yes |
| commfor-224: fp32 vs fp16 weights | 0.0137 | 0.0037 | yes |
| ai-image-detector.onnx: loadable by the app's ONNX Runtime 1.19 (IR 8 ≤ 10, opset 17 ≤ 21) | – | – | yes |
| commfor-224.onnx: loadable by the app's ONNX Runtime 1.19 (IR 8 ≤ 10, opset 17 ≤ 21) | – | – | yes |

| File | fp32 MB | Shipped (fp16 weights) MB |
|---|---|---|
| ai-image-detector.onnx | 70.1 | 35.2 |
| commfor-224.onnx | 86.8 | 43.5 |
# App ensemble: bundled + Community Forensics 224

Scores come from the exact ONNX files the app bundles (fp16 weights). The ensemble score is the mean of both models' standardized logits; standardization is fit on the photo scores and shared with video: mean_d=-0.3871, std_d=8.0011, mean_c=-3.9693, std_c=4.3742.

### Photos: each model alone vs the ensemble

| Score | AUC defactify | AUC mj-dalle-sd-nbp | AI caught @5% FA defactify | AI caught @5% FA mj-dalle-sd-nbp |
|---|---|---|---|---|
| Bundled (two views) | 0.952 | 0.773 | 73.2% | 29.7% |
| Community Forensics 224 | 0.959 | 0.654 | 83.3% | 22.8% |
| **Ensemble** | 0.991 | 0.778 | 94.8% | 33.9% |

## Preprocess: `ensemble (photos)`

| Dataset | n | AUC | ECE raw | ECE fit-on-other-dataset | ECE pooled fit | >99%/<1% raw | >99%/<1% calibrated |
|---|---|---|---|---|---|---|---|
| defactify | 1500 | 0.991 | 0.259 | 0.138 | 0.068 | 0.0% | 12.4% |
| mj-dalle-sd-nbp | 1500 | 0.778 | 0.095 | 0.170 | 0.076 | 0.0% | 1.8% |

Pooled fit: **slope = 3.1935, intercept = 0.1634**  (P(ai) = sigmoid(slope · (ai − human) + intercept))

Worst case over every dataset × condition, calibrated scores:

| Threshold | Real images ≥ threshold (would show HIGH) | AI images < threshold (would show LOW) |
|---|---|---|
| 0.05 | 94.4% | 1.2% |
| 0.10 | 81.6% | 6.8% |
| 0.15 | 73.6% | 10.0% |
| 0.20 | 64.4% | 13.2% |
| 0.25 | 55.2% | 17.6% |
| 0.30 | 49.2% | 22.4% |
| 0.35 | 45.6% | 24.4% |
| 0.40 | 40.0% | 26.4% |
| 0.45 | 34.4% | 30.8% |
| 0.50 | 29.6% | 35.2% |
| 0.55 | 24.8% | 38.4% |
| 0.60 | 20.0% | 44.0% |
| 0.65 | 16.4% | 48.8% |
| 0.70 | 14.0% | 51.2% |
| 0.75 | 11.2% | 59.6% |
| 0.80 | 8.8% | 63.6% |
| 0.85 | 6.8% | 71.2% |
| 0.90 | 2.8% | 78.8% |
| 0.95 | 0.4% | 88.4% |

Recommendation: **HIGH ≥ 0.90**, **LOW < 0.15** → worst case 2.8% of real images shown HIGH (target ≤5%), 10.0% of AI images shown LOW (target ≤10%).

At the app's bands (HIGH ≥ 0.90, LOW < 0.25), worst case: **2.8% of real shown HIGH**, 17.6% of AI shown LOW, and at least 21.2% of AI shown HIGH.

### Video: each model alone vs the ensemble

| Score | AUC deepaction | AUC df26 | AI caught @5% FA deepaction | AI caught @5% FA df26 |
|---|---|---|---|---|
| Bundled (two views) | 0.793 | 0.651 | 41.0% | 21.0% |
| Community Forensics 224 | 0.970 | 0.743 | 70.0% | 33.0% |
| **Ensemble** | 0.958 | 0.746 | 82.0% | 30.0% |

## Preprocess: `ensemble (video)`

| Dataset | n | AUC | ECE raw | ECE fit-on-other-dataset | ECE pooled fit | >99%/<1% raw | >99%/<1% calibrated |
|---|---|---|---|---|---|---|---|
| deepaction | 200 | 0.958 | 0.259 | 0.167 | 0.098 | 0.0% | 6.5% |
| df26 | 200 | 0.746 | 0.155 | 0.162 | 0.101 | 0.0% | 1.5% |

Pooled fit: **slope = 3.4921, intercept = -1.8588**  (P(ai) = sigmoid(slope · (ai − human) + intercept))

Worst case over every dataset × condition, calibrated scores:

| Threshold | Real images ≥ threshold (would show HIGH) | AI images < threshold (would show LOW) |
|---|---|---|
| 0.05 | 97.0% | 0.0% |
| 0.10 | 90.0% | 3.0% |
| 0.15 | 81.0% | 9.0% |
| 0.20 | 70.0% | 14.0% |
| 0.25 | 63.0% | 17.0% |
| 0.30 | 53.0% | 20.0% |
| 0.35 | 43.0% | 21.0% |
| 0.40 | 37.0% | 24.0% |
| 0.45 | 34.0% | 29.0% |
| 0.50 | 28.0% | 33.0% |
| 0.55 | 21.0% | 38.0% |
| 0.60 | 18.0% | 46.0% |
| 0.65 | 12.0% | 49.0% |
| 0.70 | 8.0% | 54.0% |
| 0.75 | 8.0% | 60.0% |
| 0.80 | 6.0% | 65.0% |
| 0.85 | 5.0% | 70.0% |
| 0.90 | 2.0% | 77.0% |
| 0.95 | 0.0% | 86.0% |

Recommendation: **HIGH ≥ 0.85**, **LOW < 0.15** → worst case 5.0% of real images shown HIGH (target ≤5%), 9.0% of AI images shown LOW (target ≤10%).

At the app's bands (HIGH ≥ 0.90, LOW < 0.25), worst case: **2.0% of real shown HIGH**, 17.0% of AI shown LOW, and at least 23.0% of AI shown HIGH.

# Genned detector accuracy benchmark

Model: bundled `Dafilab/ai-image-detector` ONNX export. `squash` = what the app does today; `social` = long edge ≤1080 + JPEG q75 (Instagram re-upload). Every condition then gets the app's own normalization (long edge ≤2048, JPEG q92), as on-device. Threshold 0.5 for accuracy/FPR/FNR; bands use the app's LOW <25% / HIGH ≥90% (meaningful for calibrated rows such as 'app as shipped').

> If the model was trained on one of these datasets, its numbers there are inflated. Treat a dataset that scores far better than the others with suspicion.

## Overall

| Dataset | Model | Condition | Preprocess | n (AI/real) | AUC | Accuracy | FPR (real→AI) | FNR (AI missed) | Real shown HIGH | AI shown LOW | ECE | Scores >99% or <1% |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| deepaction | app as shipped | video | video5 | 100/100 | 0.793 | 69.5% | 17.0% | 44.0% | 0.0% | 3.0% | 0.089 | 0.0% |
| deepaction | commfor-224 (app onnx) | video | video5 | 100/100 | 0.970 | 78.5% | 0.0% | 43.0% | 0.0% | 30.0% | 0.197 | 38.5% |
| defactify | app as shipped | original | avg | 250/250 | 0.951 | 84.8% | 6.4% | 24.0% | 0.0% | 6.4% | 0.102 | 1.0% |
| defactify | app as shipped | jpeg75 | avg | 250/250 | 0.952 | 85.2% | 6.4% | 23.2% | 0.0% | 6.4% | 0.102 | 1.0% |
| defactify | app as shipped | social | avg | 250/250 | 0.952 | 85.2% | 6.4% | 23.2% | 0.0% | 6.4% | 0.102 | 1.0% |
| defactify | commfor-224 (app onnx) | original | native | 250/250 | 0.960 | 78.4% | 0.8% | 42.4% | 0.0% | 34.8% | 0.209 | 54.8% |
| defactify | commfor-224 (app onnx) | jpeg75 | native | 250/250 | 0.959 | 78.0% | 1.2% | 42.8% | 0.0% | 35.6% | 0.208 | 54.0% |
| defactify | commfor-224 (app onnx) | social | native | 250/250 | 0.959 | 78.0% | 1.2% | 42.8% | 0.0% | 35.6% | 0.208 | 54.0% |
| df26 | app as shipped | video | video5 | 100/100 | 0.651 | 57.5% | 59.0% | 26.0% | 0.0% | 1.0% | 0.096 | 0.0% |
| df26 | commfor-224 (app onnx) | video | video5 | 100/100 | 0.743 | 61.5% | 3.0% | 74.0% | 0.0% | 68.0% | 0.353 | 49.5% |
| mj-dalle-sd-nbp | app as shipped | original | avg | 250/250 | 0.802 | 71.8% | 40.4% | 16.0% | 2.8% | 4.0% | 0.092 | 0.0% |
| mj-dalle-sd-nbp | app as shipped | jpeg75 | avg | 250/250 | 0.744 | 66.6% | 40.8% | 26.0% | 2.4% | 7.6% | 0.090 | 0.0% |
| mj-dalle-sd-nbp | app as shipped | social | avg | 250/250 | 0.772 | 68.0% | 44.8% | 19.2% | 2.8% | 3.2% | 0.093 | 0.0% |
| mj-dalle-sd-nbp | commfor-224 (app onnx) | original | native | 250/250 | 0.707 | 56.8% | 1.2% | 85.2% | 0.8% | 80.4% | 0.406 | 63.2% |
| mj-dalle-sd-nbp | commfor-224 (app onnx) | jpeg75 | native | 250/250 | 0.624 | 54.2% | 1.2% | 90.4% | 0.4% | 85.6% | 0.440 | 66.6% |
| mj-dalle-sd-nbp | commfor-224 (app onnx) | social | native | 250/250 | 0.629 | 54.8% | 1.2% | 89.2% | 0.8% | 84.4% | 0.435 | 69.0% |

## Per generator (bundled: squash; candidates: native)

Share classified correctly at 0.5 (for `real`: share *not* flagged as AI).

### deepaction · app as shipped

| Generator | video |
|---|---|
| real | 83.0% (n=100) |
| BDAnimateDiffLightning | 94.1% (n=17) |
| CogVideoX5B | 23.5% (n=17) |
| RunwayML | 76.5% (n=17) |
| StableDiffusion | 56.2% (n=16) |
| Veo | 58.8% (n=17) |
| VideoPoet | 25.0% (n=16) |

### deepaction · commfor-224 (app onnx)

| Generator | video |
|---|---|
| real | 100.0% (n=100) |
| BDAnimateDiffLightning | 100.0% (n=17) |
| CogVideoX5B | 35.3% (n=17) |
| RunwayML | 23.5% (n=17) |
| StableDiffusion | 81.2% (n=16) |
| Veo | 47.1% (n=17) |
| VideoPoet | 56.2% (n=16) |

### defactify · commfor-224 (app onnx)

| Generator | original | jpeg75 | social |
|---|---|---|---|
| real | 99.2% (n=250) | 98.8% (n=250) | 98.8% (n=250) |
| dalle3 | 76.0% (n=50) | 76.0% (n=50) | 76.0% (n=50) |
| midjourney6 | 36.0% (n=50) | 36.0% (n=50) | 36.0% (n=50) |
| sd21 | 78.0% (n=50) | 78.0% (n=50) | 78.0% (n=50) |
| sd3 | 42.0% (n=50) | 42.0% (n=50) | 42.0% (n=50) |
| sdxl | 56.0% (n=50) | 54.0% (n=50) | 54.0% (n=50) |

### df26 · app as shipped

| Generator | video |
|---|---|
| real | 41.0% (n=100) |
| Grok_Imagine | 100.0% (n=10) |
| HunyuanVideo_1.5_14b_i2v | 70.0% (n=10) |
| HunyuanVideo_1.5_14b_t2v | 20.0% (n=10) |
| Kling_3.0 | 100.0% (n=10) |
| LTX_2.3_distilled_i2v | 50.0% (n=10) |
| LTX_2.3_distilled_t2v | 40.0% (n=10) |
| Veo_3.1 | 100.0% (n=10) |
| Wan_2.2_14b_i2v | 80.0% (n=10) |
| Wan_2.2_14b_t2v | 80.0% (n=10) |
| Wan_2.6 | 100.0% (n=10) |

### df26 · commfor-224 (app onnx)

| Generator | video |
|---|---|
| real | 97.0% (n=100) |
| Grok_Imagine | 40.0% (n=10) |
| HunyuanVideo_1.5_14b_i2v | 10.0% (n=10) |
| HunyuanVideo_1.5_14b_t2v | 30.0% (n=10) |
| Kling_3.0 | 30.0% (n=10) |
| LTX_2.3_distilled_i2v | 10.0% (n=10) |
| LTX_2.3_distilled_t2v | 0.0% (n=10) |
| Veo_3.1 | 30.0% (n=10) |
| Wan_2.2_14b_i2v | 10.0% (n=10) |
| Wan_2.2_14b_t2v | 30.0% (n=10) |
| Wan_2.6 | 70.0% (n=10) |

### mj-dalle-sd-nbp · commfor-224 (app onnx)

| Generator | original | jpeg75 | social |
|---|---|---|---|
| real | 98.8% (n=250) | 98.8% (n=250) | 98.8% (n=250) |
| ai | 14.8% (n=250) | 9.6% (n=250) | 10.8% (n=250) |

## Calibration (original, squash)

When the model says X%, how often is the image actually AI?

### deepaction · app as shipped

| Score bin | n | Mean score | Actually AI |
|---|---|---|---|
| 0.0-0.1 | 8 | 5.7% | 0.0% |
| 0.1-0.2 | 16 | 14.7% | 6.2% |
| 0.2-0.3 | 36 | 25.5% | 30.6% |
| 0.3-0.4 | 27 | 35.0% | 44.4% |
| 0.4-0.5 | 40 | 45.5% | 50.0% |
| 0.5-0.6 | 31 | 54.6% | 58.1% |
| 0.6-0.7 | 24 | 64.5% | 91.7% |
| 0.7-0.8 | 12 | 72.2% | 83.3% |
| 0.8-0.9 | 6 | 84.9% | 100.0% |
| 0.9-1.0 | 0 | n/a | n/a |

### deepaction · commfor-224 (app onnx)

| Score bin | n | Mean score | Actually AI |
|---|---|---|---|
| 0.0-0.1 | 109 | 2.1% | 15.6% |
| 0.1-0.2 | 11 | 12.6% | 90.9% |
| 0.2-0.3 | 10 | 24.2% | 50.0% |
| 0.3-0.4 | 7 | 35.2% | 71.4% |
| 0.4-0.5 | 6 | 45.1% | 100.0% |
| 0.5-0.6 | 8 | 55.1% | 100.0% |
| 0.6-0.7 | 1 | 69.6% | 100.0% |
| 0.7-0.8 | 10 | 74.7% | 100.0% |
| 0.8-0.9 | 5 | 84.9% | 100.0% |
| 0.9-1.0 | 33 | 98.7% | 100.0% |

### defactify · commfor-224 (app onnx)

| Score bin | n | Mean score | Actually AI |
|---|---|---|---|
| 0.0-0.1 | 306 | 1.2% | 20.6% |
| 0.1-0.2 | 24 | 14.8% | 87.5% |
| 0.2-0.3 | 10 | 26.5% | 90.0% |
| 0.3-0.4 | 4 | 33.1% | 100.0% |
| 0.4-0.5 | 10 | 44.7% | 90.0% |
| 0.5-0.6 | 10 | 54.7% | 90.0% |
| 0.6-0.7 | 9 | 65.4% | 88.9% |
| 0.7-0.8 | 15 | 75.6% | 100.0% |
| 0.8-0.9 | 20 | 86.0% | 100.0% |
| 0.9-1.0 | 92 | 97.9% | 100.0% |

### df26 · app as shipped

| Score bin | n | Mean score | Actually AI |
|---|---|---|---|
| 0.0-0.1 | 0 | n/a | n/a |
| 0.1-0.2 | 2 | 15.1% | 0.0% |
| 0.2-0.3 | 5 | 25.4% | 40.0% |
| 0.3-0.4 | 19 | 36.3% | 26.3% |
| 0.4-0.5 | 41 | 44.3% | 46.3% |
| 0.5-0.6 | 42 | 55.6% | 42.9% |
| 0.6-0.7 | 51 | 65.4% | 52.9% |
| 0.7-0.8 | 28 | 74.7% | 64.3% |
| 0.8-0.9 | 11 | 83.4% | 90.9% |
| 0.9-1.0 | 1 | 90.7% | 100.0% |

### df26 · commfor-224 (app onnx)

| Score bin | n | Mean score | Actually AI |
|---|---|---|---|
| 0.0-0.1 | 153 | 1.5% | 41.2% |
| 0.1-0.2 | 7 | 13.5% | 42.9% |
| 0.2-0.3 | 5 | 25.7% | 60.0% |
| 0.3-0.4 | 3 | 33.9% | 66.7% |
| 0.4-0.5 | 3 | 42.7% | 100.0% |
| 0.5-0.6 | 4 | 54.1% | 75.0% |
| 0.6-0.7 | 4 | 62.1% | 75.0% |
| 0.7-0.8 | 2 | 76.7% | 50.0% |
| 0.8-0.9 | 6 | 85.8% | 100.0% |
| 0.9-1.0 | 13 | 95.8% | 100.0% |

### mj-dalle-sd-nbp · commfor-224 (app onnx)

| Score bin | n | Mean score | Actually AI |
|---|---|---|---|
| 0.0-0.1 | 411 | 1.0% | 43.1% |
| 0.1-0.2 | 27 | 15.5% | 70.4% |
| 0.2-0.3 | 11 | 23.8% | 72.7% |
| 0.3-0.4 | 6 | 35.0% | 100.0% |
| 0.4-0.5 | 5 | 45.6% | 60.0% |
| 0.5-0.6 | 5 | 54.1% | 100.0% |
| 0.6-0.7 | 1 | 65.5% | 100.0% |
| 0.7-0.8 | 4 | 74.1% | 100.0% |
| 0.8-0.9 | 7 | 85.2% | 85.7% |
| 0.9-1.0 | 23 | 97.7% | 91.3% |

