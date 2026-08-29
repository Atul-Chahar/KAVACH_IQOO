package com.kavach.app

import android.app.Application

/**
 * Application entry point. Manual constructor injection only — no Hilt/Dagger
 * (CLAUDE.md §Stack). Object graph wiring lands here when the first real
 * dependency appears.
 */
class KavachApplication : Application()
