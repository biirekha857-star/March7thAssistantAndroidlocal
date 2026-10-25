// 诊断：打印 Gradle 实际使用的目录
// 用法: gradlew -I diag.init.gradle.kts help

gradle.settingsEvaluated {
    println("=== [diag] settingsEvaluated ===")
    println("  gradleUserHomeDir = ${gradle.gradleUserHomeDir}")
    println("  gradleHomeDir     = ${gradle.gradleHomeDir}")
    println("  startParam.home   = ${gradle.startParameter.gradleUserHomeDir}")
}

gradle.projectsEvaluated {
    println("=== [diag] projectsEvaluated ===")
    println("  gradleUserHomeDir = ${gradle.gradleUserHomeDir}")
}
