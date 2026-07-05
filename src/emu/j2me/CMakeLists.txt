add_library(j2me
    include/j2me/applist.h
    include/j2me/common.h
    include/j2me/interface.h
    include/j2me/kmidrun.h
    src/applist.cpp
    src/common.cpp
    src/interface.cpp
    src/kmidrun.cpp)

target_include_directories(j2me PUBLIC include)
# Link epocservs so j2me sources can reach services/applist/* headers.
# Without this, kmidrun.cpp's launch_through_kmidrun() — which calls
# applist_server::launch_app to bootstrap a properly-wired Symbian
# process — fails with "services/applist/applist.h file not found".
target_link_libraries(j2me PRIVATE common config epocio epockern miniz sqlite3 epoc epocservs)