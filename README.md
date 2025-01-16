# TradeMC Plugin

Плагин для интеграции Minecraft-сервера с платформой TradeMC. Обрабатывает покупки и автоматически выдает награды игрокам.

## 📋 Требования

- Java 21+
- Bukkit/Spigot/Paper 1.12 - 1.21.+
- MySQL (опционально)

## ⚙️ Установка

1. Скачайте последнюю версию плагина из [Releases](https://github.com/DimaSergeew/TradeMc/releases)
2. Поместите JAR файл в папку `plugins`
3. Перезапустите сервер
4. Настройте `config.yml`:
5. 
   ```yaml
   shops: "ВАШ_ID_МАГАЗИНА"
   callback-key: "ВАШ_CALLBACK_KEY"
   ```

## 🛠 Команды

- `/trademc reload` - Перезагрузка конфигурации
- `/trademc check` - Проверка статуса API
- `/trademc getOnline` - Статус онлайн магазина
- `/trademc history` - История последних покупок
- `/trademc debugPurchase <игрок> <itemId> <название>` - Тестовая покупка

## 🔒 Права доступа

- `trademc.use` - Базовый доступ к командам (по умолчанию: все)
- `trademc.admin` - Административный доступ (по умолчанию: op)

## 🔧 Для разработчиков

### Callback API

Для разработчиков:

```java
// Обработка входящего callback
- public void handlePurchaseCallback(String jsonStr) {
- JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
- String buyer = obj.get("buyer").getAsString();
- JsonArray items = obj.get("items").getAsJsonArray();
// Обработка покупки...
}
```
### События
- Покупки обрабатываются асинхронно
- Выдача наград происходит синхронно в основном потоке
- Поддерживается отложенная выдача наград оффлайн игрокам

## ⚠️ Важные заметки

- Минимальный интервал проверки покупок: 30 секунд
- Callback-сервер работает на порту 8080 по умолчанию
- Все транзакции логируются в `plugins/TradeMC/logs/trademc.log`

## 📝 Конфигурация

Основные настройки в `config.yml`:
# MySQL настройки...
```yaml
check-interval-seconds: 60
retry-attempts: 3
callback:
enabled: true
port: 8080
mysql:
enabled: false
```

## 🤝 Поддержка

При возникновении проблем создавайте Issue в репозитории или обращайтесь к разработчикам TradeMC.

