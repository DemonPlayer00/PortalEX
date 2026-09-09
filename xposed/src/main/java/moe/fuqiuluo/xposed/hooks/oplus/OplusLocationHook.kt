package moe.fuqiuluo.xposed.hooks.oplus

import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.hooks.blindhook.BlindHookLocation.invoke
import moe.fuqiuluo.xposed.hooks.fused.ThirdPartyLocationHook

object OplusLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        ThirdPartyLocationHook(classLoader)
    }
}