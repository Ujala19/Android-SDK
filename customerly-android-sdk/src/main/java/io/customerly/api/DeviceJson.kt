package io.customerly.api

/*
 * Copyright (C) 2017 Customerly
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.Context
import android.os.Build
import io.customerly.BuildConfig
import io.customerly.utils.CUSTOMERLY_API_VERSION
import io.customerly.utils.CUSTOMERLY_SOCKET_VERSION
import org.json.JSONObject

/**
 * Created by Gianni on 02/05/18.
 * Project: Customerly-KAndroid-SDK
 */
internal object DeviceJson {
    internal val json: JSONObject by lazy {
        JSONObject().put("os", "Android")
                .put("device", String.format("%s %s (%s)", Build.MANUFACTURER, Build.MODEL, Build.DEVICE))
                .put("os_version", Build.VERSION.SDK_INT)
                .put("sdk_version", BuildConfig.VERSION_NAME)
                .put("api_version", CUSTOMERLY_API_VERSION)
                .put("socket_version", CUSTOMERLY_SOCKET_VERSION)
    }
    internal fun loadContextInfos(context: Context) {
        this.json.apply {
            val pm = context.packageManager
            this.put("app_name", try {
                context.applicationInfo.loadLabel(pm).toString()
            } catch (exception: Exception) {
                "<Error retrieving the app name>"
            })
            this.put("app_package", context.packageName)
            this.put("app_version", try {
                pm.getPackageInfo(context.packageName, 0).also { packageInfo ->
                    this.put("app_version", packageInfo.versionName)
                    this.put("app_build", packageInfo.versionCode)
                }
                context.applicationInfo.loadLabel(pm).toString()
            } catch (exception: Exception) {
                "<Error retrieving the app name>"
            })
        }
    }
}