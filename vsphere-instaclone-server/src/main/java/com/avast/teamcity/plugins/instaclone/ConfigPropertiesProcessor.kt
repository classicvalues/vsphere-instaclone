package com.avast.teamcity.plugins.instaclone

import jetbrains.buildServer.serverSide.InvalidProperty
import jetbrains.buildServer.serverSide.PropertiesProcessor
import org.springframework.util.StringUtils

/**
 *
 * @author Vitasek L.
 */
class ConfigPropertiesProcessor(
    private val pluginClassLoader: ClassLoader,
    private val icCloudClientFactory: ICCloudClientFactory
) :
    PropertiesProcessor {
    override fun process(propsMap: MutableMap<String, String>): MutableCollection<InvalidProperty> {
        val result = mutableListOf<InvalidProperty>()
        ICCloudClientFactory.REQUIRED_PROPS.forEach {
            checkIfNotEmpty(propsMap[it], result, it)
        }

        propsMap[ICCloudClientFactory.PROP_IMAGES]?.let { json ->
            try {
                val icImageConfigMap = ICCloudClientFactory.parseIcImageConfig(json)

                if (icImageConfigMap.isEmpty()) {
                    result.add(
                        InvalidProperty(
                            ICCloudClientFactory.PROP_IMAGES,
                            "There is no image specification defined"
                        )
                    )
                }

                validateImageConfig(icImageConfigMap, result)

                icImageConfigMap
            } catch (e: Exception) {
                result.add(InvalidProperty(ICCloudClientFactory.PROP_IMAGES, "Parsing JSON error: ${e.message}"))
            }
        }
        if (result.isEmpty()) {
            doConnectionTest(propsMap, result)
        }
        return result
    }

    private fun doConnectionTest(
        propsMap: MutableMap<String, String>,
        result: MutableList<InvalidProperty>
    ) {
        try {
            pluginClassLoader.inContext {
                val vimPort = icCloudClientFactory.getVimPort(propsMap[ICCloudClientFactory.PROP_SDKURL])
                val vim = VimWrapper(
                    vimPort,
                    propsMap[ICCloudClientFactory.PROP_USERNAME]!!,
                    propsMap[ICCloudClientFactory.PROP_PASSWORD]!!,
                    pluginClassLoader
                )
                vim.connectionLoginTest()
            }
        } catch (e: Exception) {
            result.add(
                InvalidProperty(
                    ICCloudClientFactory.PROP_CONNECTION_FAILED,
                    "Failed to validate connection: ${e.message}"
                )
            )
        }
    }

    private fun checkIfNotEmpty(
        propValue: String?,
        result: MutableList<InvalidProperty>,
        propertyName: String
    ): Boolean {
        if (!StringUtils.hasText(propValue)) {
            result.add(InvalidProperty(propertyName, "Value cannot be empty"))
            return false
        }
        return true
    }

    private fun validateImageConfig(
        icImageConfigMap: Map<String, ICImageConfig>,
        result: MutableList<InvalidProperty>
    ) {
        icImageConfigMap.entries.forEach { (image, icImageConfig) ->
            if (StringUtils.isEmpty(icImageConfig.template)) {
                result.add(
                    InvalidProperty(
                        ICCloudClientFactory.PROP_IMAGES,
                        "$image - `template` property cannot be empty"
                    )
                )
            } else {
                icImageConfig.template?.let {
                    if (it.lastIndexOf('/') == -1) {
                        result.add(
                            InvalidProperty(
                                ICCloudClientFactory.PROP_IMAGES,
                                "$image - Invalid `template` path definition"
                            )
                        )
                    }
                }
            }
        }
    }

}