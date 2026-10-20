package com.uniplay.androidauto

import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors

/**
 * Builds the official Google Android Auto Service Discovery Response protobuf message.
 * Tells the connected Android phone our display resolution (1280x720, 60fps, H.264),
 * audio channels (48kHz Media, 16kHz Speech), and input touchscreen capabilities.
 */
object AapServiceDiscovery {

    const val CHANNEL_CONTROL = 0
    const val CHANNEL_VIDEO = 1
    const val CHANNEL_AUDIO_SYSTEM = 2
    const val CHANNEL_AUDIO_SPEECH = 3
    const val CHANNEL_AUDIO_MEDIA = 4
    const val CHANNEL_INPUT = 5
    const val CHANNEL_SENSOR = 6

    fun buildResponse(width: Int = 1280, height: Int = 720, densityDpi: Int = 160): Control.ServiceDiscoveryResponse {
        val builder = Control.ServiceDiscoveryResponse.newBuilder()
            .setHeadUnitMake("Uniplay")
            .setHeadUnitModel("AutoReceiver")
            .setHeadUnitSoftwareBuild("1.0.0")
            .setHeadUnitSoftwareVersion("1.0.0")

        // 1. Video Service (Channel 1)
        val videoService = Control.Service.newBuilder().apply {
            id = CHANNEL_VIDEO
            mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
                availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                audioType = Media.AudioStreamType.NONE
                availableWhileInCall = true
                addVideoConfigs(
                    Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                        codecResolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
                        frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._60
                        setDensity(densityDpi)
                        setPixelAspectRatioE4(10000)
                        setMarginWidth(0)
                        setMarginHeight(0)
                        setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                    }.build()
                )
            }.build()
        }.build()
        builder.addServices(videoService)

        // 2. Media Audio Service (Channel 4) - 48kHz Stereo 16-bit PCM
        val mediaAudioService = Control.Service.newBuilder().apply {
            id = CHANNEL_AUDIO_MEDIA
            mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
                availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                audioType = Media.AudioStreamType.MEDIA
                addAudioConfigs(
                    Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 48000
                        numberOfBits = 16
                        numberOfChannels = 2
                    }.build()
                )
            }.build()
        }.build()
        builder.addServices(mediaAudioService)

        // 3. Speech / Guidance Audio Service (Channel 3) - 16kHz Mono 16-bit PCM
        val speechAudioService = Control.Service.newBuilder().apply {
            id = CHANNEL_AUDIO_SPEECH
            mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
                availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                audioType = Media.AudioStreamType.SPEECH
                addAudioConfigs(
                    Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                )
            }.build()
        }.build()
        builder.addServices(speechAudioService)

        // 4. Touchscreen Input Service (Channel 5)
        val inputService = Control.Service.newBuilder().apply {
            id = CHANNEL_INPUT
            inputSourceService = Control.Service.InputSourceService.newBuilder().apply {
                touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                    setWidth(width)
                    setHeight(height)
                }.build()
                // Keycodes supported: Back, Home, Play, Pause, Next, Prev
                addAllKeycodesSupported(listOf(4, 3, 85, 87, 88, 126, 127))
            }.build()
        }.build()
        builder.addServices(inputService)

        // 5. Sensor Service (Channel 6) - Driving Status (Car Parked / In Motion)
        val sensorService = Control.Service.newBuilder().apply {
            id = CHANNEL_SENSOR
            sensorSourceService = Control.Service.SensorSourceService.newBuilder().apply {
                addSensors(
                    Control.Service.SensorSourceService.Sensor.newBuilder().apply {
                        type = Sensors.SensorType.DRIVING_STATUS
                    }.build()
                )
            }.build()
        }.build()
        builder.addServices(sensorService)

        return builder.build()
    }
}
