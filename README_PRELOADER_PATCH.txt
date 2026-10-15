IMPORTANT: Preloader is a git submodule.

After unzipping, copy the patched file over the submodule source:

  cp app/src/main/cpp/Preloader/src/pl/PreLoader.cpp \
     app/src/main/cpp/Preloader/src/pl/PreLoader.cpp

If the submodule path differs, find PreLoader.cpp:

  find app -name 'PreLoader.cpp'

Replace that file with the one from this zip, then commit and push so CI rebuilds libpreloader.so.
