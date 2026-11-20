# Vanadium Updater

No-root app that installs and updates **Vanadium Browser** and **Vanadium Config** (GrapheneOS) on regular Android.
Files are downloaded from the Vanadium repository on GitLab and installed with Android's PackageInstaller (signature checked by the system).

Vanadium WebView cannot be registered as the system WebView without root, so it is only reported in the app.
arm64 devices only. Vanadium is not officially supported outside GrapheneOS.
