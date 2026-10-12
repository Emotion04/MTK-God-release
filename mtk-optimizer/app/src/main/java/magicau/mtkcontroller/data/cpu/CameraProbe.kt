package magicau.mtkcontroller.data.cpu

import magicau.mtkcontroller.data.privilege.PrivilegeManager

object CameraProbe {
    suspend fun capture(): String = PrivilegeManager.cameraProbe()
}
