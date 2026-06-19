#!/usr/bin/env python3
"""Generate TFLite models for TPU Playground."""
import os
import numpy as np
import tensorflow as tf

SIZES = [64, 128, 256, 512]
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    for n in SIZES:
        inp = tf.keras.Input(shape=(n,), name="input")
        x = inp
        for i in range(4):
            x = tf.keras.layers.Dense(n, activation=None, use_bias=True, name=f"dense_{i}")(x)
        model = tf.keras.Model(inputs=inp, outputs=x)

        # Float32
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        tflite_model = converter.convert()
        path = os.path.join(OUTPUT_DIR, f"matmul_{n}.tflite")
        with open(path, "wb") as f:
            f.write(tflite_model)
        print(f"  {path} ({len(tflite_model)} bytes)")

        # Int8 quantized (EdgeTPU-friendly)
        converter_q = tf.lite.TFLiteConverter.from_keras_model(model)
        converter_q.optimizations = [tf.lite.Optimize.DEFAULT]
        converter_q.representative_dataset = lambda n=n: (
            [np.random.randn(1, n).astype(np.float32)] for _ in range(100)
        )
        converter_q.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        converter_q.inference_input_type = tf.int8
        converter_q.inference_output_type = tf.int8
        try:
            tflite_q = converter_q.convert()
            qpath = os.path.join(OUTPUT_DIR, f"matmul_{n}_int8.tflite")
            with open(qpath, "wb") as f:
                f.write(tflite_q)
            print(f"  {qpath} ({len(tflite_q)} bytes) [INT8]")
        except Exception as e:
            print(f"  INT8 failed for {n}: {e}")

    print("Done.")

if __name__ == "__main__":
    main()
