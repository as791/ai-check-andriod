# ONNX Runtime uses JNI; keep its Java bindings so native method signatures resolve.
-keep class ai.onnxruntime.** { *; }

# Room-generated code and kotlinx.serialization use reflection-adjacent codegen that
# is already annotation-processed at compile time; standard AGP consumer rules from
# each library cover the rest. Nothing app-specific is obfuscation-sensitive here
# because DTOs are all plain data classes with no reflection-based (de)serialization
# outside kotlinx.serialization, which ships its own consumer ProGuard rules.
