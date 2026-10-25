# 只锁按名查找的入口

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ==================== JNI（libbridge.so）====================
#
# ## ⚠️ 这里必须用 `{ *; }` 而不是 `{ native <methods>; }`
#
# `libbridge.so` 的 `JNI_OnLoad` 里是这样找类的：
#
# ```cpp
# // app/src/main/native/bridge.cpp
# static constexpr char kNativeBridgeClass[] = "com/march7th/hkrpg/bridge/NativeBridgeLib";
# static constexpr char kDriverClass[]       = "com/march7th/hkrpg/core/DriverClass";
# env->FindClass(kNativeBridgeClass)   // ← 按**字符串**查找
# ```
#
# 所以 **类名本身**不能变，方法名也不能变（`RegisterNatives` 按名字注册）。
#
# 之前写成 `{ native <methods>; }` 只保住了方法体，**类名照样被混淆**，
# 结果 `FindClass` 返回 null → `JNI_OnLoad` 返回 `JNI_ERR` →
# 运行时报 `UnsatisfiedLinkError: JNI_ERR returned from JNI_OnLoad`，
# 整个 `shizuku_service` 进程会跟着崩掉。
#
# 而编译期**完全不会报错**，只有真机跑起来才暴露。
-keep class com.march7th.hkrpg.bridge.NativeBridgeLib { *; }

# libbridge FindClass + GetStaticMethodID
#
# ⚠️ 包名是 `core` 不是 `maa` —— 之前的规则写错成 `.maa.DriverClass`，
# 等于什么都没保护到。
-keep class com.march7th.hkrpg.core.DriverClass { *; }

# JNA：方法名即 C 符号；嵌套 Callback 整包留
-keep class com.march7th.hkrpg.maa.** { *; }
# libjnidispatch 只 FindClass 顶层；ptr/internal/win32 交给 R8
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.Structure { <fields>; }
-keepclassmembers class * implements com.sun.jna.Callback { <methods>; }
-dontwarn java.awt.**

# Shizuku / Root 按类名拉起
-keep class com.march7th.hkrpg.remote.RemoteServiceImpl { <init>(); }
-keep class com.march7th.hkrpg.remote.LogcatCaptureServiceImpl { <init>(); }
-keep class com.march7th.hkrpg.root.RemoteServiceStarter {
    public static void main(java.lang.String[]);
}
-keep class com.march7th.hkrpg.root.RootUserService { *; }
-keep class com.march7th.hkrpg.root.RootServiceBootstrapProvider { *; }

# AIDL
-keep class com.march7th.hkrpg.RemoteService { *; }
-keep class com.march7th.hkrpg.RemoteService$Stub { *; }
-keep class com.march7th.hkrpg.MaaCoreService { *; }
-keep class com.march7th.hkrpg.MaaCoreService$Stub { *; }
-keep class com.march7th.hkrpg.MaaCoreCallback { *; }
-keep class com.march7th.hkrpg.MaaCoreCallback$Stub { *; }
-keep class com.march7th.hkrpg.ILogcatService { *; }
-keep class com.march7th.hkrpg.ILogcatService$Stub { *; }
-keep class com.march7th.hkrpg.ITouchEventCallback { *; }
-keep class com.march7th.hkrpg.ITouchEventCallback$Stub { *; }
-keep class com.march7th.hkrpg.remote.PermissionGrantRequest { *; }
-keep class com.march7th.hkrpg.remote.PermissionStateInfo { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

# SMTP：ServiceLoader / mailcap 点名的实现，imap/pop3 缺一个就炸
-keep class org.eclipse.angus.mail.smtp.** { *; }
-keep class org.eclipse.angus.mail.imap.** { *; }
-keep class org.eclipse.angus.mail.pop3.** { *; }
-keep class org.eclipse.angus.mail.handlers.** { *; }
-keep class org.eclipse.angus.mail.util.MailStreamProvider { *; }
-keep class org.eclipse.angus.activation.*RegistryProviderImpl { *; }
-dontwarn org.eclipse.angus.**
-dontwarn jakarta.**
-dontwarn javax.**

# 任务覆盖编辑器：Gson 解 language-configuration；OnigRegExp 依赖 joni 静态初始化
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn org.joni.**
-dontwarn org.jcodings.**

# FakeContext / ShellContentResolver：acquireProvider 等对编译期不可见，R8 当死代码删掉
-keep class com.march7th.hkrpg.third.FakeContext { *; }
-keep class com.march7th.hkrpg.third.FakeContext$* { *; }
-keepclassmembers class * extends android.content.ContentResolver {
    *** acquireProvider(...);
    *** acquireUnstableProvider(...);
    *** releaseProvider(...);
    *** releaseUnstableProvider(...);
    *** unstableProviderDied(...);
}

# xzakota 焦点/超级岛模板靠 kotlinx.serialization 反射字段
-keep class com.xzakota.hyper.notification.** { *; }

# 落盘 Enum.name / valueOf
-keepclassmembers enum com.march7th.hkrpg.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
