# PiPup Module Development Guide

This guide describes how to create and integrate a new module into the PiPup ecosystem using the modular "Gold Standard."

## 1. Implement PiPupModule

Create a new class that implements the `PiPupModule` interface.

```kotlin
class MyAwesomeModule : PiPupModule {
    override val id: String = "awesome"
    override val nameRes: Int = R.string.my_module_name
    override val descriptionRes: Int = R.string.my_module_desc

    override val supportedModes: List<ModuleMode> = listOf(ModuleMode.OFF, ModuleMode.ON)
    override val defaultMode: ModuleMode = ModuleMode.OFF

    override fun onEnable(context: ModuleContext) {
        // Initialization logic
    }

    override fun onDisable() {
        // Teardown logic
    }
}
```

## 2. Register the Module

Add your module instance to `PiPupApp.kt`:

```kotlin
moduleManager.registerModule(MyAwesomeModule())
```

## 3. UI Integration (Optional)

### Global Settings Tabs

If your module adds settings to existing tabs (like General or Performance), return metadata with a `category`:

```kotlin
override fun getSettingsMetadata() = listOf(
    ModuleSettingDefinition(
        key = "my_setting",
        type = SettingType.BOOLEAN,
        labelRes = R.string.label,
        category = SettingCategory.GENERAL
    )
)
```

### Dedicated Module Submenu

To have a separate entry in the settings rail, implement `getSettingsMenu()`:

```kotlin
override fun getSettingsMenu() = ModuleMenuDefinition(
    iconRes = R.drawable.my_icon,
    labelRes = R.string.my_menu_label,
    priority = 100,
    layoutRes = R.layout.my_custom_layout // Use R.layout.submenu_module_dynamic for generic toggle list
)
```

If you use a custom `layoutRes`, remember to register your controller in `SettingsActivity.moduleSubmenuFactories`.

## 4. HTTP Routes (Optional)

Define routes your module should handle:

```kotlin
override val supportedRoutes: List<String> = listOf("/my-endpoint")

override fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
    if (session.uri == "/my-endpoint") {
        return NanoHTTPD.newFixedLengthResponse("Hello!")
    }
    return null
}
```

## 5. Permissions (Optional)

If your module requires specific permissions, return their keys:

```kotlin
override fun getRequiredPermissions() = listOf(Permissions.KEY_OVERLAY)
```

New permission keys must be implemented in the centralized `Permissions.kt` manager.
