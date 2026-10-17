import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 签名只从 git 之外的文件读。keystore 与密码放在仓库根 signing/，该目录已被
// .gitignore 忽略；把密码写进这里等于提交进版本历史，而泄密的签名等于白建 ——
// 换签名会让所有用户必须卸载重装，丢失本地方案、设置和报告。
val keystorePropsFile = rootProject.file("signing/keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    val props = Properties()
    keystorePropsFile.inputStream().use { stream -> props.load(stream) }
    props
} else null

android {
    namespace = "magicau.mtkcontroller"
    compileSdk = 37

    defaultConfig {
        applicationId = "magicau.mtkcontroller"
        minSdk = 33
        targetSdk = 37
        // Bump hand-off APKs so reports identify the exact installed revision.
        versionCode = 150
        versionName = "0.5.1"
        // 证据等级低于这个值的功能在设置页不列出。
        //
        // **按构建类型定，不按“正式/非正式”定**：canary（debug）是给先锋用户试的，
        // 不该把“预计无效”的东西摆在面前；release 是交付包，列出来的都要能用 ——
        // 但发布前没法逐台真机复验，所以 release 不设门槛，靠 Evidence 标签说明。
        // 默认值跟着构建类型走；`-PminEvidence=` 可以单独覆盖（例如发一个纯验证包）。
        buildConfigField(
            "String",
            "MIN_EVIDENCE",
            "\"" + (providers.gradleProperty("minEvidence").getOrElse("LIKELY_USELESS")) + "\"",
        )
    }

    testOptions {
        unitTests {
            // Robolectric 要读 res/ 才能让 Context.getString 真的返回 strings.xml 里的文案。
            // 不开这个，所有资源 id 都解析不出来，测试里看到的只会是占位符。
            isIncludeAndroidResources = true
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        // keystore.properties 缺失时不创建 release 签名：构建会因未签名而失败，
        // 这比静默退回 debug 签名好 —— 后者会让人在不知情的情况下发出一个
        // 任何人都能生成的签名，之后无法换成正式签名而不丢掉所有用户。
    }

    buildTypes {
        release {
            // 逆向成本必须高于重新推导的成本：本项目 data/ 层的平台知识是数月真机
            // 实测的结果，而 release 包此前完全未混淆，反编译产物与源码几乎一致。
            // 规则见 proguard-rules.pro，只 keep 跨进程契约与序列化这些必需项。
            //
            // 混淆只在 release 生效：debug 保持原样，方便日常调试与抓栈。
            //
            // **只在改动 proguard-rules.pro 之后**才需要在真机上重跑序列化
            // （方案导入导出、备份还原）与 Shizuku 提权链路。没改规则就不必重跑。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // canary 包：把证据不足的项藏起来，见 defaultConfig 里 MIN_EVIDENCE 的说明。
            buildConfigField(
                "String",
                "MIN_EVIDENCE",
                "\"" + (providers.gradleProperty("minEvidence").getOrElse("WRITTEN")) + "\"",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 的字节码版本必须和上面那一行一致。
    //
    // 不设的话 Kotlin 跟着 Gradle 守护进程的 JDK 走 —— 这台机器是 JDK 25，
    // 于是 Kotlin 出 Java 25 字节码（class file major version 69），而 Java 源码
    // 是 17。两边不一致平时看不出问题（DEX 编译器都收），但 Robolectric 的
    // 插桩 classloader 只认到 Java 21，一跑测试就炸
    // "Unsupported class file major version 69"。
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

// Robolectric 4.14.1 自带 ASM 9.7.1，只认到 Java 24 字节码。
// 而 Gradle 守护进程跑在 JDK 25 上，插桩时要读 JDK 自己的类（v69），
// 于是每个测试都在 teardown 里炸
// "Unsupported class file major version 69" —— 断言其实都跑过了，挂在收尾。
// ASM 9.9 起支持 Java 25。ASM 只在测试类路径上，不进 APK。
configurations.configureEach {
    resolutionStrategy {
        force("org.ow2.asm:asm:9.9.1")
        force("org.ow2.asm:asm-tree:9.9.1")
        force("org.ow2.asm:asm-analysis:9.9.1")
        force("org.ow2.asm:asm-commons:9.9.1")
        force("org.ow2.asm:asm-util:9.9.1")
    }
}

// 规范只有真的跑到才算规范 —— 靠人记得手动执行等于没有。挂在 `preBuild` 上，
// 让每次构建都过一遍；检查内容见 docs/dev/wording.md 与 docs/CONTRIBUTING.md。
//
// 用 Gradle 自带的 `Exec` 而不是 `doLast {}`：后者会捕获构建脚本对象，
// 使配置缓存失效（本仓库开着配置缓存）。
//
// 路径在配置期算好 —— 执行期再读 `rootProject` 同样会破坏配置缓存。
// 检查脚本自己按脚本位置解析仓库根，所以工作目录不影响结果。
val repoRoot = rootProject.projectDir.parentFile

val checkDocs by tasks.registering(Exec::class) {
    group = "verification"
    description = "检查文档链接、锚点、总索引可达性与代码标识符"
    commandLine("python", repoRoot.resolve("tools/check_docs.py").absolutePath)
}

val checkCopy by tasks.registering(Exec::class) {
    group = "verification"
    description = "检查资源引用、分层文案审计与基线回归"
    commandLine("python", repoRoot.resolve("tools/check_strings.py").absolutePath)
}

// 没装 Python 的机器可以用 `-PskipChecks` 跳过；正常开发不要用。
val skipChecks = providers.gradleProperty("skipChecks").isPresent
if (!skipChecks) {
    tasks.named("preBuild") { dependsOn(checkDocs, checkCopy) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 单元测试里的 Context 只能靠它。`CpuControl` 和 `SmartPlatform.report`
    // 都拿 Context 去 getString，而 Context 有 103 个抽象方法，纯 JUnit 伪造不出来。
    // 不改成注入解析器是因为那要动 CPU 控制路径 —— 那条路径不许为测试便利而改。
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
