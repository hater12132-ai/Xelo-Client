#include "HookManager.h"
#include <exception>

extern "C" void ExecuteProgram(void) {
    static int isHooked = 0;
    if (isHooked == 1) {
        // Best-effort hooks for pairip / logo — never crash launch on new MC versions
        try {
            core::hookTimer();
        } catch (...) {}
        try {
            core::setupHooks();
        } catch (...) {}
        try {
            core::patchMinecraftLogo();
        } catch (...) {}
        return;
    }
    ++isHooked;
}
