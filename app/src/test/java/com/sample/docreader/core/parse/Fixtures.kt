package com.sample.docreader.core.parse

/** 测试夹具加载：generateFixtures task 已把夹具放到 test resources 的 fixtures/ 下。 */
internal object Fixtures {
    fun bytes(name: String): ByteArray =
        Fixtures::class.java.classLoader!!.getResource("fixtures/$name")?.readBytes()
            ?: error("fixture 不存在：$name")
}
