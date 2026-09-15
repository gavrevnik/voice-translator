package com.sayit.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SayItTheme {
                val translatorViewModel: TranslatorViewModel = viewModel()
                TranslatorScreen(translatorViewModel)
            }
        }
    }
}
