package com.avast.teamcity.plugins.instaclone

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.vmware.vim25.VimPortType
import com.vmware.vim25.VimService
import jetbrains.buildServer.clouds.*
import jetbrains.buildServer.clouds.server.CloudEventAdapter
import jetbrains.buildServer.clouds.server.CloudEventDispatcher
import jetbrains.buildServer.serverSide.AgentDescription
import jetbrains.buildServer.serverSide.BuildAgentManager
import jetbrains.buildServer.serverSide.PropertiesProcessor
import jetbrains.buildServer.serverSide.SBuildAgent
import jetbrains.buildServer.serverSide.agentPools.AgentPoolManager
import jetbrains.buildServer.web.openapi.PluginDescriptor
import java.util.*
import javax.xml.ws.BindingProvider

fun <T> ClassLoader.inContext(block: () -> T): T {
    val currentThread = Thread.currentThread()
    val prevClassLoader = currentThread.contextClassLoader
    currentThread.contextClassLoader = this
    try {
        return block()
    } finally {
        currentThread.contextClassLoader = prevClassLoader
    }
}

class ICCloudClientFactory(
    private val pluginClassLoader: ClassLoader,
    private val pluginDescriptor: PluginDescriptor,
    cloudEventDispatcher: CloudEventDispatcher,
    private val agentPoolManager: AgentPoolManager,
    private val buildAgentManager: BuildAgentManager
) : CloudClientFactory {

    private val defaultImagesJson: String = javaClass.getResource("/samples/imageProfileConfig.json")!!.readText()

    private val vimService = pluginClassLoader.inContext {
        VimService()
    }

    init {
        cloudEventDispatcher.addListener(object : CloudEventAdapter() {
            override fun instanceAgentMatched(
                profile: CloudProfile,
                instance: CloudInstance,
                agent: SBuildAgent
            ) {
                if (instance is ICCloudInstance) {
                    instance.matchedAgentId = agent.id
                }
            }
        })
    }

    fun getVimPort(sdkUrl: String?): VimPortType {
        val vimPort = vimService.vimPort
        val requestContext = (vimPort as BindingProvider).requestContext
        requestContext[BindingProvider.ENDPOINT_ADDRESS_PROPERTY] = sdkUrl
        requestContext[BindingProvider.SESSION_MAINTAIN_PROPERTY] = true
        return vimPort
    }

    override fun createNewClient(
        cloudState: CloudState,
        cloudClientParameters: CloudClientParameters
    ): CloudClientEx {
        return pluginClassLoader.inContext {
            val profileUuid = cloudClientParameters.getParameter(PROP_PROFILE_UUID)!!
            val sdkUrl = cloudClientParameters.getParameter(PROP_SDKURL)
            val vimPort = getVimPort(sdkUrl)
            val username = cloudClientParameters.getParameter(PROP_USERNAME)!!
            val password = cloudClientParameters.getParameter(PROP_PASSWORD)!!
            val imageConfig = cloudClientParameters.getParameter(PROP_IMAGES)!!
            val vim = VimWrapper(vimPort, username, password, pluginClassLoader)
            ICCloudClient(vim, buildAgentManager, agentPoolManager, profileUuid, parseIcImageConfig(imageConfig))
        }
    }

    override fun getCloudCode(): String {
        return CLOUD_CODE
    }

    override fun getDisplayName(): String {
        return "VMware Instaclone"
    }

    override fun getEditProfileUrl(): String {
        return pluginDescriptor.getPluginResourcesPath("vmware-instaclone-profile-settings.html")
    }

    override fun getInitialParameterValues(): Map<String, String> {
        val params = mutableMapOf<String, String>()
        params[PROP_IMAGES] = defaultImagesJson
        params[PROP_PROFILE_UUID] = UUID.randomUUID().toString()
        return params
    }


    override fun canBeAgentOfType(agentDescription: AgentDescription): Boolean {
        val config = agentDescription.configurationParameters
        return config.containsKey("vsphere-instaclone.instance.uuid")
    }

    override fun getPropertiesProcessor(): PropertiesProcessor {
        // perform validation
        return ConfigPropertiesProcessor(pluginClassLoader, this)
    }


    companion object {
        internal const val CLOUD_CODE = "vmic"
        internal const val PROP_CONNECTION_FAILED = "vmwareInstacloneConnectionInfo"
        internal const val PROP_SDKURL = "vmwareInstacloneSdkUrl"
        internal const val PROP_USERNAME = "vmwareInstacloneUsername"
        internal const val PROP_PASSWORD = "vmwareInstaclonePassword"
        internal const val PROP_PROFILE_UUID = "vmwareInstacloneProfileUuid"
        internal const val PROP_IMAGES: String = "vmwareInstacloneImages"

        val REQUIRED_PROPS = arrayOf(PROP_SDKURL, PROP_USERNAME, PROP_PASSWORD, PROP_IMAGES)

        private val mapper = jacksonObjectMapper()

        fun parseIcImageConfig(imageConfig: String): Map<String, ICImageConfig> {
            return mapper.readValue(imageConfig)
        }
    }

}