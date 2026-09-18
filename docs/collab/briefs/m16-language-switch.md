# M16 实现简报 — 应用内语言切换（F4.9）

> 你是本项目的实现者。本文档是你的**唯一指令文件**，严格按任务清单执行。
> 设计已定稿（设计文档 REV15 §3.7「应用内语言切换」、需求 REV12 F4.9、决策 D-2026-09-18-01），**不要重开设计**；发现指令与代码冲突时在最终回复中报告，不要自行变更范围。

## 背景

F4.6/M14 落地后系统语言自动切换已可用，但用户无法在应用内选择语言（§3.7 原写「本期不做应用内手动切换」，该范围注记随 F4.9 解除）。目标：全局设置页增「语言」行——跟随系统（默认）/ 中文 / 英文，选中即时生效并持久化；手动选择优先于系统语言，直至改回「跟随系统」；资源对（`values` / `values-zh`）**零改动**，纯切换机制。

真机参考：Android 13（SDK 33），33+ 框架路径可在设备上直接验证。

## 硬性约束

1. **只许修改**：`app/src/main/`、`app/src/test/`、`app/src/androidTest/`。**不得 git 提交/暂存/检出**。
2. **不得修改**新增测试 `LocaleSwitchTest.kt` / `SettingsLanguageUiTest.kt` / `LocaleSwitchDeviceTest.kt` 的任何断言（红测试先行）。既有测试最多允许**编译性适配**，不得弱化断言。
3. 不引 AppCompat、不引任何新依赖、不声明 `android:localeConfig`；不动 `values`/`values-zh` 已有关键值与键集对等性（`LocalizationTest` 会回归）。
4. 不联网、无新权限；注释/文档/提交信息用中文。

## 契约（签名与键名不得改，随红测试提交）

| 契约 | 位置 | 说明 |
| --- | --- | --- |
| `enum class AppLanguage { SYSTEM, ZH, EN }` | `core/model/Models.kt` | 语言三档 |
| `SettingsRepository.appLanguage: Flow<AppLanguage>` | `core/settings/SettingsRepository.kt` | DataStore 键 `app_language`（BCP-47：`zh`/`en`），缺失/未知 = `SYSTEM` |
| `SettingsRepository.saveAppLanguage(language)` | 同上 | 独立键写入；`SYSTEM` 时**移除**该键；`save(ReadingSettings)` 不触碰此键 |
| `LocaleSwitcher.tagOf(AppLanguage): String?` | `core/settings/LocaleSwitcher.kt` | `SYSTEM→null`、`ZH→"zh"`、`EN→"en"` |
| `LocaleSwitcher.languageOf(String?): AppLanguage` | 同上 | 未知/缺失 → `SYSTEM` |
| `LocaleSwitcher.next(AppLanguage): AppLanguage` | 同上 | 跟随系统→中文→English→跟随系统 |
| `LocaleSwitcher.wrapBaseContext(Context): Context` | 同上 | 读持久值；跟随系统时**原样返回**，否则 `createConfigurationContext` 包装 |
| `LocaleSwitcher.applyToFramework(Context, AppLanguage)` | 同上 | API<33 为空操作；33+ 调 `LocaleManager.setApplicationLocales`（`SYSTEM` → 空 `LocaleList`），失败静默回退 |
| 字符串键 `settings_general_section` / `settings_language` / `settings_language_system` / `settings_language_zh` / `settings_language_en` | `res/values/strings.xml` + `res/values-zh/strings.xml` | zh 值：通用 / 语言 / 跟随系统 / 中文 / English；默认（英文）值：General / Language / System default / 中文 / English |

## 任务清单

### T1 数据与机制

- `AppLanguage` 入 `core/model/Models.kt`（与 `ReaderFont`/`WrapMode`/`ReadingTheme` 同处）。
- `SettingsRepository`：新增 `Keys.APP_LANGUAGE`（`stringPreferencesKey("app_language")`）、`appLanguage` flow（经 `LocaleSwitcher.languageOf` 映射，未知值不抛异常）、`saveAppLanguage`（`SYSTEM` 走 `p.remove(...)`）。
- `LocaleSwitcher`（新文件 `core/settings/LocaleSwitcher.kt`）：
  - `wrapBaseContext`：同步读一次 `SettingsRepository(context).appLanguage.first()`（`runBlocking`，仅 Activity 构造期调用），按标签 `Configuration(base.resources.configuration).setLocale(Locale.forLanguageTag(tag))` → `base.createConfigurationContext(config)`。
  - `applyToFramework`：`Build.VERSION.SDK_INT >= 33` 才执行，`getSystemService(LocaleManager::class.java)` 为 null 时返回；整体包 `runCatching`（OEM/测试环境不崩）。
  - 暴露 `needsManualRecreate: Boolean`（= `SDK_INT < 33`）供 UI 判断是否自行 `recreate()`。
- `MainActivity`：`override fun attachBaseContext(newBase: Context)` → `super.attachBaseContext(LocaleSwitcher.wrapBaseContext(newBase))`。注意 Manifest 的 `configChanges` 不含 `locale`，无需改动。

### T2 设置页语言行

- `ui/SettingsScreen.kt`：内容区**顶部**新增「通用」小节 + `CycleSettingRow`（标题 `settings_language`、值 = 当前档位标签、点击 → 下一档），复用既有 `CycleSettingRow`（cd 与行文本同名，≥48dp，无需新控件）。位置放顶部：Robolectric 默认可视区内点击可达。
- 点击处理：`scope.launch { val next = LocaleSwitcher.next(settings.language); repository.saveAppLanguage(next); LocaleSwitcher.applyToFramework(context, next); if (LocaleSwitcher.needsManualRecreate) (context as? Activity)?.recreate() }`——**必须等写入完成再 recreate/框架调用**，否则重建会读到旧值。
- 档位标签映射：`AppLanguage.SYSTEM → settings_language_system`、`ZH → settings_language_zh`、`EN → settings_language_en`（与既有 `themeLabel`/`fontLabel` 同风格，私有 composable 函数）。
- 可加一句值旁边的说明（可选，如不加以免超出键契约）。

### T3 资源

- 两文件各补 5 个键（见契约表），键集严格对等（`LocalizationTest.stringKeySets_parityBetweenDefaultAndZh` 会校验）；zh 值中的「中文」「English」逐字沿用（语言名以本语言书写，双语一致）。

### T4 自检（必做，结果写进最终回复）

```bash
./gradlew testDebugUnitTest             # 全绿，且新增例数 = 红态例数
./gradlew compileDebugAndroidTestKotlin # 设备端可编译
```

- 红测试文件：`app/src/test/java/com/sample/docreader/LocaleSwitchTest.kt`、`app/src/test/java/com/sample/docreader/ui/SettingsLanguageUiTest.kt`、`app/src/androidTest/java/com/sample/docreader/LocaleSwitchDeviceTest.kt`。
- 若既有测试因实现而红，修实现，**不得改测试断言**。

## 完成报告要求

回复中列出：改动文件清单（按 T1~T3 分组）、红 → 绿实测计数、`./gradlew testDebugUnitTest` 完整结果、与本文档冲突或拿不准的点。
