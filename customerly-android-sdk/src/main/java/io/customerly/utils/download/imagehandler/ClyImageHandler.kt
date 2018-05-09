package io.customerly.utils.download.imagehandler

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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.support.annotation.UiThread
import android.util.LruCache
import android.util.SparseArray
import io.customerly.utils.ggkext.useSkipExeption
import java.io.File
import java.io.FileFilter
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Created by Gianni on 16/04/18.
 * Project: Customerly-KAndroid-SDK
 */

private const val MAX_DISK_CACHE_SIZE = 1024 * 1024 * 2
private const val MAX_LRU_CACHE_SIZE = 1024 * 1024 * 2

internal object ClyImageHandler {
    private val LOCK = arrayOfNulls<Any>(0)
    private val lruCache = LruCache<String, Bitmap>(MAX_LRU_CACHE_SIZE)
    private val pendingDiskRequests = SparseArray<ClyImageRequest>()
    private val pendingNetworkRequests = SparseArray<ClyImageRequest>()
    private var executorService: ExecutorService = Executors.newFixedThreadPool(5)
    private var diskCacheSize = -1L

    @UiThread
    internal fun request(request: ClyImageRequest) {
        assert(request.handlerValidateRequest())

        val hashCode = request.handlerGetHashCode
        synchronized(LOCK) {
            this.pendingDiskRequests.remove(hashCode)
            this.pendingNetworkRequests.remove(hashCode)
        }

        val diskKey = request.handlerGetDiskKey()
        try {
            this.lruCache.get(diskKey)?.takeUnless { it.isRecycled }
        } catch (ignored: OutOfMemoryError) {
            null
        }?.let {
            request.handlerOnResponse(bmp = it)
        } ?: this.handleDisk(request = request, hashCode = hashCode, diskKey = diskKey)
    }

    private fun handleDisk(request: ClyImageRequest, hashCode : Int, diskKey: String) {
        synchronized(LOCK) {
            this.pendingDiskRequests.put(hashCode, request)
            this.pendingNetworkRequests.remove(hashCode)
        }
        this.executorService.submit {
            synchronized(LOCK) {
                /*
                In questo modo anche se nel frattempo c'è stata una successiva richiesta per la stessa ImageView (es: ImageView riciclata da un'adapter di una RecyclerView), viene elaborata la richiesta più recente.
                Atomicamente viene anche rimossa la richiesta, quindi il callback pendente nell'handler quando verrà eseguito a questo punto del codice troverà null e al successivo check req != null interromperà l'esecuzione
                 */
                this.pendingDiskRequests.get(hashCode)?.also {
                    this.pendingDiskRequests.remove(hashCode)
                }
            }?.let { request ->
                //Searching image in cache (LRU and Disk)
                try {
                    request.customerlyCacheDirPath.let { cacheDirPath ->
                        val bitmapFile = File(cacheDirPath, diskKey)
                        if (bitmapFile.exists()) {
                            if (System.currentTimeMillis() - bitmapFile.lastModified() < 24 * 60 * 60 * 1000) {
                                BitmapFactory.decodeFile(bitmapFile.toString())?.also { bmp ->
                                    request.handlerOnResponse(bmp = bmp)
//                                        //Add Bitmap to LruMemory
                                    this.lruCache.put(diskKey, bmp)
                                }
                            } else {
                                bitmapFile.delete()
                                this.handleNetwork(request = request, hashCode = hashCode, diskKey = diskKey)
                            }
                        } else {
                            this.handleNetwork(request = request, hashCode = hashCode, diskKey = diskKey)
                        }
                    }
                } catch (ignored: OutOfMemoryError) {
                    this.handleNetwork(request = request, hashCode = hashCode, diskKey = diskKey)
                }
            }
        }
    }

    private fun handleNetwork(request: ClyImageRequest, hashCode : Int, diskKey: String) {
        synchronized(LOCK) {
            this.pendingNetworkRequests.put(hashCode, request)
        }
        this.executorService.submit {
            synchronized(LOCK) {
                /*
                In questo modo anche se nel frattempo c'è stata una successiva richiesta per la stessa ImageView (es: ImageView riciclata da un'adapter di una RecyclerView), viene elaborata la richiesta più recente.
                Atomicamente viene anche rimossa la richiesta, quindi il callback pendente nell'handler quando verrà eseguito a questo punto del codice troverà null e al successivo check req != null interromperà l'esecuzione
                 */
                this.pendingNetworkRequests.get(hashCode)?.also {
                    this.pendingNetworkRequests.remove(hashCode)
                }
            }?.let { request ->
                if(!try {
                    val connection = (URL(request.url).openConnection() as HttpURLConnection).also {
                        it.readTimeout = 5000
                        it.doInput = true
                        it.instanceFollowRedirects = true
                        it.connect()
                    }
                    BitmapFactory.decodeStream(connection.inputStream)
                            ?.let { request.handlerApplyResize(it) }
                            ?.let { request.handlerApplyTransformations(it) }
                            ?.let { bmp ->
                                if (null != synchronized(LOCK) {
                                            this.pendingDiskRequests.get(hashCode)
                                                    ?: this.pendingNetworkRequests.get(hashCode)
                                        }) {
                                    // Nel frattempo che scaricava l'immagine da internet ed eventualmente applicava resize e transformCircle è staa fatta una nuova richiesta
                                    // per la stessa ImageView quindi termina l'esecuzione attuale invalidando la bmp
                                    bmp.recycle()
                                } else {
                                    request.handlerOnResponse(bmp = bmp)

                                    //Caching image (LRU and Disk)
                                    this.lruCache.put(diskKey, bmp)

                                    request.customerlyCacheDirPath.let { cacheDirPath ->
                                        val cacheDir = File(cacheDirPath)
                                        if (!cacheDir.exists()) {
                                            cacheDir.mkdirs()
                                            try {
                                                File(cacheDirPath, ".nomedia").createNewFile()
                                            } catch (ignored: IOException) {
                                            }
                                        }

                                        val bitmapFile = File(cacheDirPath, diskKey)
                                        FileOutputStream(bitmapFile).useSkipExeption {
                                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                                            if (this.diskCacheSize == -1L) {
                                                this.diskCacheSize = cacheDir.listFiles(FileFilter { it.isFile }).asSequence().map { it.length() }.sum()
                                            } else {
                                                this.diskCacheSize += bitmapFile.length()
                                            }
                                        }
                                        if (this.diskCacheSize > MAX_DISK_CACHE_SIZE) {
                                            cacheDir.listFiles(FileFilter { it.isFile }).minBy { it.lastModified() }?.let {
                                                val fileSize = it.length()
                                                if (it.delete()) {
                                                    this.diskCacheSize -= fileSize
                                                }
                                            }
                                        }
                                    }
                                }
                                true
                            } == true
                } catch (exception: Throwable) {
                    false
                }) {
                    request.handlerLoadError()
                }
            }
        }
    }

}