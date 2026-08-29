package com.kavach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kavach.app.ui.KavachTheme
import com.kavach.app.ui.SkeletonScreen

/** Single activity, per CLAUDE.md §Stack. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KavachTheme {
                SkeletonScreen()
            }
        }
    }
}
