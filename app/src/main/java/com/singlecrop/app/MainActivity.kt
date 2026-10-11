package com.singlecrop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.singlecrop.app.ui.CropScreen
import com.singlecrop.app.ui.SingleCropTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SingleCropTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CropScreen(vm)
                }
            }
        }
    }
}
