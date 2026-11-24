add_library(disasm
        include/disasm/disasm.h
        src/disasm.cpp)

target_include_directories(disasm PUBLIC include)
target_include_directories(disasm PUBLIC ${capstone_INCLUDE_DIRS})

if (TARGET capstone_static)
    target_link_libraries(disasm PRIVATE capstone_static common)
else()
    target_link_libraries(disasm PRIVATE capstone common)
endif()
