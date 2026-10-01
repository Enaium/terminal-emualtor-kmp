/*
 * ConPTY entry points for the Windows pseudoconsole.
 *
 * windows.h (and its wincontypes.h) already defines HPCON and, on SDKs that
 * carry the pseudoconsole API, the three functions below; the declarations
 * here repeat them with the exact same prototypes so the bindings exist even
 * on MinGW sysroots whose headers predate the API. Identical redeclarations
 * are legal C.
 */
#pragma once

#include <windows.h>

HRESULT WINAPI CreatePseudoConsole(COORD size, HANDLE hInput, HANDLE hOutput, DWORD dwFlags, HPCON *phPC);
HRESULT WINAPI ResizePseudoConsole(HPCON hPC, COORD size);
void WINAPI ClosePseudoConsole(HPCON hPC);
