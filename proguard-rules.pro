# CleanBus minimal keep rules for R8 release optimization
-keepattributes *Annotation*,Signature,SourceFile,LineNumberTable

# Preserve Android Activities defined in AndroidManifest.xml
-keep public class com.geek.cleanbus.MainActivity {
    public <init>();
    *;
}

-keep public class com.geek.cleanbus.LineDetailActivity {
    public <init>();
    *;
}

# Preserve reflective Window calls and ignore platform warnings
-dontwarn java.lang.reflect.**
-dontwarn android.view.Window
-ignorewarnings
