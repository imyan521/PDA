"""Execute the real Lua include chain using the system Lua runtime."""
import ctypes
import ctypes.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


def run_lua(source):
    library = ctypes.util.find_library('lua5.3')
    if not library:
        raise unittest.SkipTest('Lua 5.3 runtime not available')
    lua = ctypes.CDLL(library)
    lua.luaL_newstate.restype = ctypes.c_void_p
    lua.luaL_openlibs.argtypes = [ctypes.c_void_p]
    lua.luaL_loadstring.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    lua.luaL_loadstring.restype = ctypes.c_int
    lua.lua_pcallk.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int,
                             ctypes.c_int, ctypes.c_longlong, ctypes.c_void_p]
    lua.lua_pcallk.restype = ctypes.c_int
    lua.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
    lua.lua_tolstring.restype = ctypes.c_char_p
    lua.lua_close.argtypes = [ctypes.c_void_p]
    state = lua.luaL_newstate()
    try:
        lua.luaL_openlibs(state)
        directory = json.dumps(str(ROOT / 'main/assets/config'))
        program = f'function include(name) return dofile({directory} .. "/" .. name) end\n' + source
        error = lua.luaL_loadstring(state, program.encode())
        if not error:
            error = lua.lua_pcallk(state, 0, 0, 0, 0, None)
        if error:
            raise AssertionError(lua.lua_tolstring(state, -1, None).decode())
    finally:
        lua.lua_close(state)


class SlamProfileTest(unittest.TestCase):
    def test_lidar_only_has_no_imu_requirement_and_has_turn_search_margin(self):
        run_lua('''
local options = include "android_2d_lidar_only.lua"
assert(options.trajectory_builder.trajectory_builder_2d.use_imu_data == false)
assert(options.tracking_frame == "base_link")
assert(TRAJECTORY_BUILDER_2D.real_time_correlative_scan_matcher.angular_search_window >= math.rad(12))
assert(TRAJECTORY_BUILDER_2D.ceres_scan_matcher.rotation_weight == 10)
assert(TRAJECTORY_BUILDER_2D.pose_extrapolator.use_imu_based == false)
''')

    def test_continue_does_not_accidentally_restore_imu_requirement(self):
        run_lua('''
local options = include "android_2d_lidar_only_continue.lua"
assert(options.trajectory_builder.trajectory_builder_2d.use_imu_data == false)
assert(options.tracking_frame == "base_link")
assert(POSE_GRAPH.optimize_every_n_nodes == 40)
assert(POSE_GRAPH.global_sampling_ratio == 0.35)
''')

    def test_imu_profile_is_preserved_for_comparison(self):
        run_lua('''
local options = include "android_2d.lua"
assert(options.trajectory_builder.trajectory_builder_2d.use_imu_data == true)
assert(options.tracking_frame == "imu")
assert(TRAJECTORY_BUILDER_2D.ceres_scan_matcher.rotation_weight == 40)
''')


if __name__ == '__main__':
    unittest.main()
