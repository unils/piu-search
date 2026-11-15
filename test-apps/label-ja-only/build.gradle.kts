// SPDX-License-Identifier: Apache-2.0

plugins {
    id("piu.test-app")
}

android {
    // aapt2 は既定の値のない資源を消し、マニフェストの参照を失敗にする（「removing resource … without required default value」）。
    // 消さないよう指示して、values-ja/ にだけ名前のある APK を作る。古い道具や別の道具で組み立てたアプリには、この形のものがある
    androidResources {
        additionalParameters += "--no-resource-removal"
    }
}
