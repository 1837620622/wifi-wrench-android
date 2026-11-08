package com.chuankangkk.wrenchlift

import androidx.activity.ComponentActivity

/**
 * 仅调试版：模拟器上 Compose 界面测试的宿主。清单里固定 sensorLandscape，一启动就是横屏；
 * 普通 ComponentActivity 不定方向，系统在测试进行中把它转成横屏会重建页面，测试就找不到界面。
 */
class LandscapeTestHostActivity : ComponentActivity()
