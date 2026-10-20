package com.uniplay.androidauto

import android.view.KeyEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors

/**
 * Builds the official Google Android Auto Service Discovery Response protobuf message.
 * Conforms to standard Google AAP Channel mapping & capabilities.
 */
object AapServiceDiscovery {

    const val CHANNEL_CONTROL = 0
    const val CHANNEL_SENSOR = 1
    const val CHANNEL_VIDEO = 2
    const val CHANNEL_INPUT = 3
    const val CHANNEL_AUDIO_SPEECH = 4
    const val CHANNEL_AUDIO_SYSTEM = 5
    const val CHANNEL_AUDIO_MEDIA = 6
    const val CHANNEL_MIC = 7
    const val CHANNEL_MEDIA_PLAYBACK = 9
    const val CHANNEL_NAVIGATION_STATUS = 10

    private val SUPPORTED_KEYCODES = listOf(
        KeyEvent.KEYCODE_SOFT_LEFT,
        KeyEvent.KEYCODE_SOFT_RIGHT,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_MUSIC,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_STOP
    )

    fun buildResponse(width: Int = 1280, height: Int = 720, densityDpi: Int = 160): Control.ServiceDiscoveryResponse {
        val builder = Control.ServiceDiscoveryResponse.newBuilder()
            .setMake("Uniplay")
            .setModel("Uniplay Auto")
            .setYear("2024")
            .setVehicleId("uniplay-hub-01")
            .setHeadUnitMake("Uniplay")
            .setHeadUnitModel("AutoReceiver")
            .setHeadUnitSoftwareBuild("1.0.0")
            .setHeadUnitSoftwareVersion("1.0.0")
            .setDriverPosition(Control.DriverPosition.DRIVER_POSITION_LEFT)
            .setCanPlayNativeMediaDuringVr(false)
            .setHideProjectedClock(false)
            .setDisplayName("Uniplay Wireless")
            .setHeadunitInfo(
                Common.HeadUnitInfo.newBuilder()
                    .setHeadUnitMake("Uniplay")
                    .setHeadUnitModel("AutoReceiver")
                    .setMake("Uniplay")
                    .setModel("Uniplay Auto")
                    .setYear("2024")
                    .setVehicleId("uniplay-hub-01")
                    .setHeadUnitSoftwareBuild("1.0.0")
                    .setHeadUnitSoftwareVersion("1.0.0")
                    .build()
            )

        // 1. Sensor Service (Channel 1)
        val sensorService = Control.Service.newBuilder().apply {
            id = CHANNEL_SENSOR
            sensorSourceService = Control.Service.SensorSourceService.newBuilder().apply {
                addSensors(
                    Control.Service.SensorSourceService.Sensor.newBuilder()
                        .setType(Sensors.SensorType.DRIVING_STATUS)
                        .build()
                )
                addSensors(
                    Control.Service.SensorSourceService.Sensor.newBuilder()
                        .setType(Sensors.SensorType.NIGHT)
                        .build()
                )
            }.build()
        }.build()
        builder.addServices(sensorService)

        // 2. Video Service (Channel 2)
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

        // 3. Touchscreen Input Service (Channel 3)
        val inputService = Control.Service.newBuilder().apply {
            id = CHANNEL_INPUT
            inputSourceService = Control.Service.InputSourceService.newBuilder().apply {
                touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                    setWidth(width)
                    setHeight(height)
                }.build()
                addAllKeycodesSupported(SUPPORTED_KEYCODES)
            }.build()
        }.build()
        builder.addServices(inputService)

        // 4. System Sounds Audio Service (Channel 5) - Required to maintain link
        val systemAudioService = Control.Service.newBuilder().apply {
            id = CHANNEL_AUDIO_SYSTEM
            mediaSinkService = Control.Service.MediaSinkService.newBuilder().apply {
                availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                audioType = Media.AudioStreamType.SYSTEM
                addAudioConfigs(
                    Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                )
            }.build()
        }.build()
        builder.addServices(systemAudioService)

        // 5. Speech / Voice Guidance Audio Service (Channel 4) - 16kHz Mono PCM
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

        // 6. Media Audio Service (Channel 6) - 48kHz Stereo PCM
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

        // 7. Microphone Source Service (Channel 7) - Required by Google Android Auto
        val micService = Control.Service.newBuilder().apply {
            id = CHANNEL_MIC
            mediaSourceService = Control.Service.MediaSourceService.newBuilder().apply {
                type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                audioConfig = Media.AudioConfiguration.newBuilder().apply {
                    sampleRate = 16000
                    numberOfBits = 16
                    numberOfChannels = 1
                }.build()
            }.build()
        }.build()
        builder.addServices(micService)

        // 8. Media Playback Status (Channel 9)
        val playbackService = Control.Service.newBuilder().apply {
            id = CHANNEL_MEDIA_PLAYBACK
            mediaPlaybackService = Control.Service.MediaPlaybackStatusService.newBuilder().build()
        }.build()
        builder.addServices(playbackService)

        // 9. Navigation Turn-by-Turn Status (Channel 10)
        val navService = Control.Service.newBuilder().apply {
            id = CHANNEL_NAVIGATION_STATUS
            navigationStatusService = Control.Service.NavigationStatusService.newBuilder()
                .setMinimumIntervalMs(1000)
                .setType(Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly)
                .build()
        }.build()
        builder.addServices(navService)

        return builder.build()
    }
}
