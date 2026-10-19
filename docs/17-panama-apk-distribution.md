# Panama APK distribution — 0.5.1

These signed preview installers select Panama on first launch. They contain the public Flight Blender and Passport addresses, with no operator account, pilot identity, aircraft serial, OAuth client secret or access token bundled into the app.

## Choose the installer

- **OpenUTM-Panama-v4-0.5.1.apk:** the DJI v4 app, including the Mini 2. Android 6 or later; ARM 32/64-bit.
- **OpenUTM-Panama-v5-0.5.1.apk:** the DJI v5 app for compatible enterprise equipment. Android 7 or later; ARM64. The coordinator must confirm the aircraft/controller combination before use.

These are two separate apps. Install the one for the operator's equipment. They are not installers for every DJI controller. Do not include the simulator or the Android test APK in an operator download.

## First installation

1. Open the correct APK on the Android device and follow Android's installation prompts. If asked, allow the file manager or browser to install this APK.
2. Open OpenUTM Flight and grant its requested permissions for the supported device.
3. **Device setup** shows `https://blender.panama.openutm.net` as the default server.
4. Prefer **Scan QR code** or **Import file** using the private device setup your coordinator provided. This can include the roster, aircraft, map downloads and updates.
5. If the coordinator supplied device credentials instead, select **Enter Panama device setup**. Enter the device name, operator ID, pilot name, client ID, client secret and applicable height limit. Aircraft details can be entered now or selected after connection. Leave registration blank if no number has been supplied; do not invent one.
6. Select **Connect to Panama**. The app checks token retrieval and a protected server read before saving the setup. A failed check leaves the form open. It does not create a declaration.
7. Install the offline map packs covering the planned location in **Device setup → Offline maps**. Connecting to Panama does not download maps automatically. A grey map means the required local pack is missing; Plan now offers a link to import one.
8. Connect the controller and grounded aircraft. In **Plan**, review the pilot, aircraft, location, height and time. **Save and declare** creates a new declaration on Panama. Check its acknowledgement before operations.

Contact the coordinator to request access. A website login alone does not supply the device's credentials. Do not distribute an operator's setup file, QR code or client secret alongside a shared APK. Store completed setup files privately and remove the transfer copy after importing.

Existing device setups are preserved on upgrade, including an explicitly selected server. A fresh install defaults to Panama; an update does not silently move another lab's flights or settings to Panama.

## Pilot profiles and administrator access

There is no pilot self-registration in this release. These are separate setup steps:

- **Passport access:** the administrator creates or approves the pilot's account. Contact your administrator to request access; entering a name in the app does not create a Passport account.
- **Pilot profile in the app:** the manual Panama setup form creates one local roster entry from the pilot name you enter and generates an internal ID. You do not need to enter an ID or a pilot registration number. This profile alone does not verify the pilot's identity or qualifications.
- **Shared devices:** ask the coordinator for a private setup file or QR code containing the approved pilot roster. There is no separate in-app screen for adding or editing multiple pilots yet. Preserve the same roster IDs when updating it so the same pilot stays identifiable across devices and future setups; repeating manual setup generates a new local ID.
- **Pilot sign-in:** the app supports administrator-configured Passport sign-in through an imported setup's `pilot_login` configuration. The simple manual Panama form does not configure this. A device's client credentials authenticate the device, not the person using it.

For the initial rollout, the coordinator should prepare each device's private setup with its operator, approved pilots and device credentials. Operators can then import or scan it once and select their name in Plan.

## Limits for this preview

Mini 2 automatic missions remain disabled in release builds. Bench checks and successful server declarations do not qualify a real flight or grant permission to fly. Offline map/terrain packs, lab-specific approvals and field validation still need to be supplied for the operation. Broadcast Remote ID is outside this release's network-reporting scope.

The APKs are signed with the locally held OpenUTM release certificate. An installed development APK uses a different signing certificate and cannot be overwritten by this release. Do not uninstall a development build with unsynced records just to change certificates; preserve/export its records and coordinate the migration first. The existing development app on the owner's phone was left in place.

## Español: instalación inicial

1. Elija el APK correcto: **v4 para el Mini 2**; **v5 para equipos empresariales compatibles**, según confirme su coordinador.
2. Abra el APK en Android y siga los pasos de instalación. Abra OpenUTM Flight y conceda los permisos necesarios.
3. En **Device setup**, el servidor predeterminado es `https://blender.panama.openutm.net`.
4. Use **Scan QR code** o **Import file** con la configuración privada que le entregue su coordinador.
5. Si recibió credenciales del dispositivo, elija **Enter Panama device setup**. Ingrese el nombre del dispositivo, identificador del operador, nombre del piloto, identificador y secreto del cliente, y el límite de altura correspondiente. Agregue los datos de la aeronave si dispone de ellos.
6. Pulse **Connect to Panama**. La aplicación comprueba el acceso antes de guardar. Este paso no crea una declaración de vuelo.
7. Importe los mapas sin conexión de su zona en **Device setup → Offline maps**. La conexión al servidor no descarga los mapas automáticamente.
8. Conecte el controlador y la aeronave en tierra. En **Plan**, revise el piloto, la aeronave, la zona, la altura y el horario. Pulse **Save and declare** y compruebe la respuesta del servidor.

Solicite el acceso a su coordinador. No comparta archivos de configuración, códigos QR ni secretos del cliente junto con un APK de distribución general. Las misiones automáticas del Mini 2 siguen deshabilitadas en esta versión. Una declaración aceptada no autoriza por sí sola un vuelo.

### Perfiles de pilotos y acceso

El administrador debe crear o aprobar el acceso a Passport; no hay autorregistro de pilotos. Al ingresar el nombre en la configuración manual, la aplicación crea un perfil local con un identificador interno. No necesita introducir un número de registro de piloto. Esto no crea una cuenta de Passport ni verifica la identidad o las cualificaciones del piloto.

Para un dispositivo compartido, solicite al coordinador un archivo privado o código QR con la lista de pilotos aprobados. Todavía no hay una pantalla para agregar o editar varios pilotos. El coordinador debe conservar sus identificadores al actualizar la lista. El inicio de sesión de pilotos en Passport necesita una configuración adicional del administrador; las credenciales del dispositivo no identifican a la persona que lo utiliza.

## Release owner: keep the signing key

The signing key is stored outside Git at `~/.openutm/openutm-release.jks`. The corresponding passwords are in `~/.gradle/gradle.properties`, protected with mode 600. Back up both in the organisation's password manager and an appropriate offline location. Never include either in the distribution folder. Future updates must use the same key.

Build the aircraft releases with JDK 17:

```sh
./gradlew :core:test :adapter-fake:test :app-v4:assembleDjiRelease :app-v5:assembleRelease
```

Verify both signatures with the SDK's `apksigner verify --print-certs` before sharing. The distribution folder includes SHA-256 checksums for the APK files and a public certificate fingerprint. These identify files and updates; they are not credentials.

The v4 release first-run smoke test uses a fresh, isolated Android emulator and fake/empty form inputs. It never submits declarations or telemetry:

```sh
./gradlew -Popenutm.releaseSmoke=true :app-v4:assembleDjiReleaseAndroidTest
# Install the matching release and androidTest APKs on the test emulator, then run:
adb -s <test-emulator> shell am instrument -w org.openutm.djiflight.v4.test/org.openutm.djiflight.test.PanamaSetupSmoke
```

Validation for the 19 October 2026 package: 148 JVM tests passed (136 core, 12 adapter), both signed aircraft releases and the simulator debug build compiled, and shared debug/v4 release/v5 release lint completed without errors. The v4 release first-run instrumentation passed on an isolated Android 14 emulator; v5 release installation and first launch also showed the Panama setup screen. Both release signatures and packaged version metadata were checked, and packaged content was checked for the owner's private credentials and identity. No production declarations, telemetry or aircraft-control commands were issued during these release checks. No v5 aircraft was qualified.

The v5 build retains vendor warnings about optional Java KML helpers referencing `org.dom4j`; these helpers are not directly called by this app. Build success and emulator startup do not qualify all vendor SDK paths or equipment combinations.
