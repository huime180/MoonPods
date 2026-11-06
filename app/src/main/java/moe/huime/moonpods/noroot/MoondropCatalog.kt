package moe.huime.moonpods.noroot

/*
 * 云端机型目录与 Root 侧共用同一份实现（原本是两份，免 root 那份是本文件的旧副本，
 * 缺少 uuid/缓存读写等后来加入的能力）。这里只保留别名，调用点无需改动。
 */
typealias MoondropCatalog = moe.huime.moonpods.pods.moondrop.MoondropCatalog
