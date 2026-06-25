cmake_minimum_required(VERSION 3.15)

project(MasterStreamer VERSION 0.0.1)

set(CMAKE_CXX_STANDARD 17)

if(NOT DEFINED JUCE_PATH)
    if(EXISTS "/Applications/JUCE")
        set(JUCE_PATH "/Applications/JUCE")
        add_subdirectory(${JUCE_PATH} JUCE)
    else()
        message(STATUS "Local JUCE not found at /Applications/JUCE. Fetching from GitHub...")
        include(FetchContent)
        FetchContent_Declare(
            JUCE
            GIT_REPOSITORY https://github.com/juce-framework/JUCE.git
            GIT_TAG 8.0.13
        )
        FetchContent_MakeAvailable(JUCE)
    endif()
else()
    add_subdirectory(${JUCE_PATH} JUCE)
endif()

juce_add_plugin(MasterStreamer
    COMPANY_NAME "ZombiePhone FX"
    BUNDLE_ID "com.zombiephonefx.masterstreamer"

    IS_SYNTH FALSE
    NEEDS_MIDI_INPUT FALSE
    NEEDS_MIDI_OUTPUT FALSE
    IS_MIDI_EFFECT FALSE

    COPY_PLUGIN_AFTER_BUILD TRUE

    PLUGIN_MANUFACTURER_CODE Zomb
    PLUGIN_CODE Mstr

    FORMATS VST3 AU

    PRODUCT_NAME "MasterStreamer"
)

juce_add_binary_data(MasterStreamer_Data
    SOURCES
        Assets/logobkr.png
)

juce_generate_juce_header(MasterStreamer)

target_sources(MasterStreamer PRIVATE
    Source/PluginProcessor.cpp
    Source/PluginEditor.cpp
)

target_link_libraries(MasterStreamer PRIVATE
    MasterStreamer_Data
    juce::juce_audio_utils
    juce::juce_dsp
    juce::juce_audio_processors
    juce::juce_gui_extra
)

target_compile_definitions(MasterStreamer PRIVATE
    JUCE_WEB_BROWSER=0
    JUCE_USE_CURL=0

    JUCE_VST3_CAN_REPLACE_VST2=0

    JucePlugin_Enable_ARA=0

    JucePlugin_Build_VST=0
    JucePlugin_Build_VST3=1
    JucePlugin_Build_AU=1
    JucePlugin_Build_AUv3=0
    JucePlugin_Build_AAX=0
    JucePlugin_Build_Standalone=0
    JucePlugin_Build_Unity=0
)
