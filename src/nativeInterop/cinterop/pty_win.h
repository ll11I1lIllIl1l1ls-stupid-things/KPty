    // Minimal ConPTY header for Kotlin/Native cinterop on Windows.
    #ifndef JPTY_PTY_WIN_H
    #define JPTY_PTY_WIN_H

    #ifndef _WIN32_WINNT
    #define _WIN32_WINNT 0x0A00
    #endif

    #include <windows.h>

    #ifdef __cplusplus
    extern "C" {
    #endif

    #ifndef _HPCON
    typedef void* HPCON;
    #endif

    HRESULT WINAPI CreatePseudoConsole(COORD size, HANDLE hInput, HANDLE hOutput,
                                       DWORD dwFlags, HPCON* phPC);
    void WINAPI ClosePseudoConsole(HPCON hPC);
    HRESULT WINAPI ResizePseudoConsole(HPCON hPC, COORD size);

    #ifdef __cplusplus
    }
    #endif

    #endif