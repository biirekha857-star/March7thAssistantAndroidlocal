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

# ==================== AIDL ====================
#
# ## 为什么每个都要 keep
#
# AIDL 生成的 `Stub` / `Proxy` 里有大量**按名传递**的东西：
#
# - `asInterface()` 靠 `descriptor` 字符串匹配接口名
# - `onTransact()` 靠 `case TRANSACTION_xxx` 分派
# - `Parcelable` 的 `CREATOR` 字段是**反射查找**的
#
# R8 把这些当死代码删掉或改名，编译期毫无提示，
# 真机跑起来就是：
#
# ```
# android.os.BadParcelableException
# java.lang.SecurityException: Binder invocation to an incorrect interface
# ```
#
# ## ⚠️ 历史坑：类名改过但规则没跟着改
#
# 接口曾叫 `MaaCoreService` / `MaaCoreCallback`，后来为了去掉 MAA 痕迹
# 改成了 `M7aCoreService` / `M7aCoreCallback`。
# 如果规则还 keep 旧名，等于**什么都没保护** —— R8 会把新名字的类
# 照删不误。所以下面每个名字都必须和 `app/src/main/aidl/` 里的文件名一致。
-keep class com.march7th.hkrpg.RemoteService { *; }
-keep class com.march7th.hkrpg.RemoteService$Stub { *; }
-keep class com.march7th.hkrpg.M7aCoreService { *; }
-keep class com.march7th.hkrpg.M7aCoreService$Stub { *; }
-keep class com.march7th.hkrpg.M7aCoreCallback { *; }
-keep class com.march7th.hkrpg.M7aCoreCallback$Stub { *; }
-keep class com.march7th.hkrpg.ILogcatService { *; }
-keep class com.march7th.hkrpg.ILogcatService$Stub { *; }
-keep class com.march7th.hkrpg.ITouchEventCallback { *; }
-keep class com.march7th.hkrpg.ITouchEventCallback$Stub { *; }
-keep class com.march7th.hkrpg.remote.PermissionGrantRequest { *; }
-keep class com.march7th.hkrpg.remote.PermissionStateInfo { *; }

# 所有 Parcelable（含 AIDL 生成的）的 CREATOR 是反射查找的，必须留
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

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

# ==================== kotlinx.serialization ====================
#
# ## 为什么必须显式 keep
#
# 本项目用 kotlinx.serialization 存配置（DataStore）和传任务参数。
# 它在编译期生成 `$serializer` 伴生对象，运行时靠 **`Companion` 上的
# `serializer()`** 反射取用。
#
# R8 会认为这些成员"没人调用"而删掉，于是：
#
# - 读配置 → `SerializationException: Serializer for class 'XxxConfig' is not found`
# - 更糟的情况：读出来是默认值，用户配置**静默丢失**
#
# 后者最难查 —— 不报错，只是设置没了。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    *** Companion;
}
# 枚举的 serializers 缓存
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    kotlinx.serialization.KSerializer serializer(...);
}

# ==================== Koin DI ====================
#
# Koin 用 reified 泛型 + 反射取构造器。R8 擦掉泛型签名或改类名后，
# 依赖注入会在启动阶段抛 `NoDefinitionFoundException`。
-keep class org.koin.** { *; }
-dontwarn org.koin.**
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}

# ==================== ONNX Runtime（OCR / 模型推理）====================
#
# ## ⚠️ 这是 release 版崩溃的头号原因
#
# 崩溃现场（取自 Android bugreport 的 tombstone）：
#
# ```
# Abort message: 'JNI DETECTED ERROR IN APPLICATION: java_class == null
#     in call to GetMethodID
#     from boolean[] ai.onnxruntime.OrtSession.run(...)'
#
# #06 libonnxruntime4j_jni.so (convertToTensorInfo+628)
# #07 libonnxruntime4j_jni.so (convertOrtValueToONNXValue+436)
# #08 libonnxruntime4j_jni.so (Java_ai_onnxruntime_OrtSession_run+836)
# ```
#
# 线程名 `starrail-task`，进程直接 SIGABRT，日志表现为
# "任务服务异常终止" —— 而且**一跑 OCR 必炸**。
#
# ## 根因
#
# `libonnxruntime4j_jni.so` 里的 C++ 用**类名**去 `FindClass`：
#
# ```cpp
# jclass cls = env->FindClass("ai/onnxruntime/TensorInfo");
# jmethodID mid = env->GetMethodID(cls, "getType", "...");
# //                    ↑ 类被删或改名后 cls == null → JNI 直接 abort
# ```
#
# 实测 release 包 dex 里 `ai.onnxruntime` 只剩 8 个类，
# `OnnxValue` 的某个子类被改名成 `a`，**`TensorInfo` 被整个删掉** ——
# Java 侧确实没人引用它，但 C++ 侧要用。
#
# 编译期毫无提示，只有真机跑到推理那一刻才炸。
#
# ## 结论
#
# ONNX Runtime 的 Java 包装层**必须整体保留**，一个字段都不能少。
# 它只有几十个类，体积代价可以忽略。
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# 同理：com.microsoft.onnxruntime 扩展包（若以后引入）
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn com.microsoft.onnxruntime.**

# ==================== 反射兜底 ====================
#
# 下面这些类在代码里是"按名字"被使用的，R8 静态分析看不到引用。
# 全部用 * 整类保留 —— 这些类都很小，体积代价可以忽略。

# 任务类型注册（按字符串 type 查表）
-keep class com.march7th.hkrpg.starrail.StarRailTaskRegistry { *; }
-keep class com.march7th.hkrpg.starrail.StarRailTasks { *; }
-keep class com.march7th.hkrpg.core.task.CoreTaskType { *; }
-keep class com.march7th.hkrpg.data.model.TaskTypeInfo { *; }
-keep class com.march7th.hkrpg.data.model.**Config { *; }

# 崩铁引擎：全量保留 —— 识别和坐标逻辑靠常量/枚举名
-keep class com.march7th.hkrpg.starrail.** { *; }

# 引擎与特权服务内部
-keep class com.march7th.hkrpg.remote.** { *; }
-keep class com.march7th.hkrpg.root.** { *; }
-keep class com.march7th.hkrpg.core.** { *; }

# 通知模板（xzakota 之外我们的 provider 也可能被按名加载）
-keep class com.march7th.hkrpg.data.notification.** { *; }

# 保留行号，崩溃栈才有意义
-keepattributes LineNumberTable,SourceFile
