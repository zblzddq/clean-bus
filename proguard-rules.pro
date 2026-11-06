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

# R8 的优化通道在 min-api 26 下会把接口方法内联成 AbstractMethodError（实测 v1.0.15/16 启动即崩）
# 保留 shrink + obfuscate，仅关闭 optimize
-dontoptimize

