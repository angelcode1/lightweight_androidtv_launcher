# Gazelle Launcher uses only Android framework APIs at runtime.

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-allowaccessmodification
-repackageclasses ''
