                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (ScanService.instance != null) ScanService.instance.goSearch();
                }, 350);
