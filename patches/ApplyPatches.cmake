# Apply patches/<name>/*.patch to a submodule source tree at configure time.
#
# Patches are applied with `git apply` in the submodule work tree. A patch that already
# applies in reverse is considered applied and skipped, so re-configuring is safe.
# To change a patch: edit the submodule sources, then regenerate it with
#   git -C <submodule> diff > patches/<name>/<file>.patch
function(fcitx5_android_apply_patches SOURCE_DIR NAME)
    set(PATCH_DIR "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/${NAME}")
    file(GLOB PATCHES "${PATCH_DIR}/*.patch")
    list(SORT PATCHES)
    foreach(PATCH ${PATCHES})
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${PATCH}")
        execute_process(
            COMMAND git apply --reverse --check "${PATCH}"
            WORKING_DIRECTORY "${SOURCE_DIR}"
            RESULT_VARIABLE ALREADY_APPLIED
            OUTPUT_QUIET ERROR_QUIET)
        if(ALREADY_APPLIED EQUAL 0)
            continue()
        endif()
        execute_process(
            COMMAND git apply "${PATCH}"
            WORKING_DIRECTORY "${SOURCE_DIR}"
            RESULT_VARIABLE APPLY_RESULT
            ERROR_VARIABLE APPLY_ERROR)
        if(NOT APPLY_RESULT EQUAL 0)
            message(FATAL_ERROR "Failed to apply ${PATCH} to ${SOURCE_DIR}: ${APPLY_ERROR}")
        endif()
        message(STATUS "Applied ${PATCH}")
    endforeach()
endfunction()
