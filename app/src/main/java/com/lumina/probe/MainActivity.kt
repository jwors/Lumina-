package com.lumina.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M0 探针屏：只回答一件事——"插线了吗，相机找到了吗"。
 * 视觉是 throwaway；M1 起被完整 UI 替换。连接状态完全由 transport 层驱动。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProbeScreen()
        }
    }
}

@Composable
fun ProbeScreen() {
    // 04 号工单将此处替换为连接状态机的真实状态
    val state = "未连接 · 请插入相机"

    Surface(color = Color(0xFFF4F2EA)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Lumina", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF20251F))
            Text(state, fontSize = 14.sp, color = Color(0xFF8D9689), modifier = Modifier.padding(top = 12.dp))
            Text("+ M0 probe", fontSize = 10.sp, color = Color(0xFFB9B3A6), modifier = Modifier.padding(top = 40.dp))
        }
    }
}
