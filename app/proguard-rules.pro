# Compose 与 Kotlin 协程由官方规则处理，这里只保留会被系统反射创建的组件
-keep class com.qqbot.app.MainActivity { *; }
-keep class com.qqbot.app.BotService { *; }
-keep class com.qqbot.app.BootReceiver { *; }
# viewModel() 通过反射拿 (Application) 构造函数；显式保留类名与构造函数，防止 R8 收掉导致启动崩溃
-keep class com.qqbot.app.ui.AppViewModel {
    <init>(android.app.Application);
}
-renamesourcefileattribute SourceFile
