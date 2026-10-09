# Wiring the ESP32-S3 board (YD-ESP32-23 / "S3-N16R8") to the inverter

Board: **YD-ESP32-23 2022-V1.3** (VCC-GND Studio style ESP32-S3 DevKitC clone), module **ESP32-S3 N16R8**
(16 MB flash, 8 MB octal PSRAM), external antenna connector, RGB LED, two USB-C ports:

| Port | What it is | Use it for |
|---|---|---|
| **COM** | USB-to-serial chip → the S3's UART0 (GPIO 43/44) | **Flashing from the PC** and seeing logs. Also fine for power. |
| **USB** | the S3's own USB (GPIO 19/20) | Not needed now. Later: USB host for Growatt inverters (needs the USB-OTG pad on the back bridged). |

Either port powers the board from the power bank (5 V). Use **one cable only**.

## Same 3 wires as the C3: nothing extra is needed for Voltronic (Inverex, Galaxy)

The inverter's RJ45 jack is a **serial (RS232) port, not Ethernet**. It only needs 3 of the 8 wires, exactly like
the C3 setup. Keep using the **same MAX3232 module and the same RJ45 cable**; only the 4 jumper wires on the
board side move.

```
 Inverter RJ45 (cable)          MAX3232 module              ESP32-S3 board
 ─────────────────────          ──────────────              ──────────────
 pin 1 (inverter TX)  ───────►  RS232 RX in  (R1IN)
 pin 2 (inverter RX)  ◄───────  RS232 TX out (T1OUT)
 pin 8 (GND)          ────────  GND (RS232 side)
                                VCC  ─────────────────────► 3V3   (left header, top)
                                GND  ─────────────────────► GND
                                TXD  ─────────────────────► GPIO 18   (left header, "18")
                                RXD  ◄───────────────────── GPIO 17   (left header, "17")
```

| MAX3232 pin | ESP32-S3 pin | On the C3 it was |
|---|---|---|
| VCC | **3V3** | 3V3 |
| GND | **GND** | GND |
| TXD (data from the inverter) | **GPIO 18** | GPIO 10 |
| RXD (data to the inverter) | **GPIO 17** | GPIO 20 |

The RJ45 side (pins 1, 2, 8 into the MAX3232) stays exactly as it is now.

## Don't use these pins

- **35, 36, 37**: used internally by the 8 MB PSRAM on N16R8 boards.
- **0, 3, 45, 46**: boot/strapping pins (0 is the BOOT button).
- **19, 20**: the "USB" port. **43, 44 (TX/RX labels)**: the "COM" port.
- **48**: the RGB LED.
- Never connect RS232 (±12 V) straight to the board: always through the MAX3232.
- Never connect 5 V to a GPIO pin. The MAX3232 runs on **3V3**.

## More wires later? Only for other inverter brands

Voltronic inverters (Inverex Veyron, Galaxy, Knox, Crown…) need just these 3 wires: more wires would not add
anything. Brands that use **RS485 Modbus** (Inverex Nitrox / Deye-type, Growatt, SolaX, Solis, Sungrow, Huawei)
need a separate small **RS485 module** (e.g. MAX485 / auto-direction TTL-to-RS485, ~Rs 150–250) on spare pins
(planned: GPIO 4 = RX, 5 = TX, 6 = direction) and a cable with that brand's pinout. That comes with the
multi-brand drivers (see `product-pitch/Multi-Brand-Driver-Plan.md`), not now.

## First start (Claude does the software part)

1. Plug the **COM** port into the PC with a data USB-C cable (not charge-only). Claude flashes the S3 build of
   the firmware and uploads the web app.
2. The board starts a Wi-Fi hotspot **SolarMonitor-XXXX** (password in the setup notes). Join it from a phone,
   pick the home Wi-Fi in the page that opens.
3. Move the board to the inverter, wire it as above, power it from the power bank.
4. Claude restores the saved settings and bills from the C3 backup.
5. Keep the C3 as it is (don't erase it): it is the fallback until the S3 has run for a few days.
