# NoLag düzeltmeleri (29 Ekim 2026)

## Kod değişiklikleri

- C-01: Oyuncu mesafesi kontrolü oyuncu API'sinden okunuyor; tanımsız `world` değişkeni kaldırıldı.
- B-01 / H-01 / H-02: Eşya birleştirmesi PDC ve bellekteki expiry kaydını birlikte yeniliyor. Kuyruk UUID başına tek giriş tutuyor; çalışmayan entity görevleri 10 saniye sonra yeniden deneniyor. Silme anında PDC tekrar kontrol ediliyor.
- B-02: Paper'da AI geri yüklemesi doğrudan yapılıyor; yüklü chunk'lardaki kalıcı işaretler de kapanışta taranıyor.
- B-03: IP başına eşzamanlı TCP sınırı proxy uyumluluğu için varsayılan kapalı. Doğrudan bağlantılarda isteğe bağlı etkinleştirilebilir.
- B-04: Oyuncu `send-view-distance` değerini Nether koruması yönetiyor; Chunk modülü bu değeri yazmıyor. Nether restore işlemi başka bir değişikliği ezmiyor.
- B-05: Claim/region izinlerini güvenilir biçimde doğrulamadan ek yaprak kırılması yapılamadığı için hızlı yaprak çürümesi kullanılmıyor. Vanilla çürümesi çalışır; anahtar açılırsa uyarı yazılır.
- B-06: Redstone baskılama eşiği yapılandırılan TPS değerine eşitlendi.
- H-03: EntityCulling incelemesi 64 entity bütçesini oyuncu başına döndürüyor; inceleme dışındaki yakındaki entity'ler yanlışlıkla görünür yapılmıyor. Görünürlük API hatalarında kayıt tekrar denenecek şekilde korunuyor.
- H-04: TAB limitinde bağlantı sessizce askıda bırakılmıyor; kanal kapatılıyor.
- H-05: Prefetch timeout taraması yalnızca chunk geçişinde ve en fazla 10 saniyede bir yapılıyor.
- H-06 / H-08: RAM sweep başarısızlığı komut ve Dashboard'a bildiriliyor; Dashboard envanter işlemleri tık olayından sonraki tick'e erteleniyor.
- H-07: Activity yüzdesi 0–100 ölçeğinde hesaplanıyor.
- `/nolag cleanup` izlenen, süresi dolmuş eşyalar için en fazla 2048 güvenli görev başlatıyor; sonuç sayısı başlatılan görev sayısıdır, silinen öğe sayısı değildir.
- Netty erken hook, başarısız denemeler tükendikten sonra yapılandırma reload'unda tekrar deneniyor.
- Autosave `enabled: true` isteği desteklenmediğinde açık uyarı veriyor. Eski mob stack isimlerinde yalnızca tanınan NoLag etiketi temizleniyor; yeni saklanan orijinal isimler ayrıştırılırken geri getiriliyor.
- Lag spike log'u ölçtüğü scheduler aralığını doğru adlandırıyor.

## Bilinçli sınırlar

MobStacker yeni sanal mob birleştirmesi, ek World.save döngüsü, fiziksel flood temizliği ve bazı Folia modülleri veri bütünlüğü/thread güvenliği gerekçesiyle kapalı kalır. Eski stack'lerde daha önce kaybedilmiş özgün mob adları geri üretilemez. Claim entegrasyonu olmadan hızlı yaprak çürümesi çalıştırılmaz.

## Doğrulama

`python3 scripts/static_regression_checks.py` geçti. Bu ortamda Maven ve JDK 21 derleyicisi bulunmadığından `mvn clean verify`, Paper 1.21.8 ve Folia canlı testleri yapılamadı. JAR üretimi ve oyun içi davranış doğrulaması için bu adımlar hâlâ gereklidir.
