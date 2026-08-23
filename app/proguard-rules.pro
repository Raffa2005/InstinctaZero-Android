-keepattributes *Annotation*

# Tink references these source-analysis-only annotations in class metadata.
# They are not needed at runtime, and the artifact intentionally does not ship them.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
