package com.apex.agent.di

import javax.inject.Qualifier

/** Native Plugin Host 2.0 的宿主级协程域限定符（SupervisorJob 隔离）。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class PluginHostScope
