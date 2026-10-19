# Connect the Android app to Panama

The phone sends flight declarations and network telemetry to **one configured Flight Blender server**. Connecting a DJI controller does not select a server or create a declaration.

Panama services:

| Service | Address | Purpose |
| --- | --- | --- |
| Flight Blender | <https://blender.panama.openutm.net> | Flight declarations and network telemetry |
| Flight Passport | <https://auth.panama.openutm.net/accounts/login/> | Administrator-approved accounts and device credentials |
| Spotlight | <https://spotlight.panama.openutm.net/> | View operations, subject to your access |

## 1. Get the phone's setup from your administrator

Ask your Panama administrator for an **OpenUTM device setup file or QR code for this phone**. If you need a Passport account, contact your administrator. Do not use public self-sign-up as an onboarding step.

A Passport website login alone does not configure the Android app. The phone needs its own OAuth application credentials to upload when a pilot is not signed in. Keep cloud gateway credentials on the gateway; use a separate, revocable application for the phone.

The administrator supplies the device name, operator ID, approved pilot roster, aircraft serial/registration, and applicable height limit. Optional pilot sign-in, maps and update channels can also be included. Do not invent registration details or reuse the development pilot/operator.

For administrators: [the Panama template](../provisioning/examples/panama-device.template.json) has the server addresses and scopes filled in. It deliberately has blank credentials, identity fields and a zero height limit, so the app refuses it until completed. Confirm the height limit for the operation; the template specifies no approved limit. Save completed profiles outside Git, using the ignored `*.openutm-device.json` extension. The file and QR code contain a client secret; share them privately.

## 2. Import the setup while grounded

1. Land and finish any flight before changing servers.
2. Connect the phone to the internet.
3. Open the app's **Menu → Device setup, maps, updates → This device**.
4. Choose **Import file** and select the administrator's file. Alternatively, choose **Scan QR code** or **Paste code**.
5. Check that **UTM server** says `https://blender.panama.openutm.net`, and that the lab, operator, pilots and aircraft are correct.
6. Remove the downloaded setup file after import. The app keeps its copy encrypted in private storage.

The same server address also appears in **Menu → Network status**. “Online” means the phone has a network; it does not prove that authentication, declaration approval or telemetry upload succeeded.

## 3. Confirm access before operations

Have the administrator confirm that the phone's credentials can obtain a token from Passport and read a protected Flight Blender endpoint. This check does not require creating a declaration or transmitting telemetry. A successful read does not prove write access or downstream processing.

When you are ready for a real, authorized operation:

1. Create a **new plan** with the correct aircraft, pilot, area and time window.
2. Use **Save and declare**. Check that Flight Blender accepts the declaration before proceeding with the operation.
3. Review the app's pre-flight checks and normal operational approvals. A server accepting a declaration is not regulatory permission to fly.
4. During a planned flight, the app records aircraft states and sends network telemetry when the declaration is active and connectivity allows. Offline samples stay on the phone for retry, subject to the declaration window.
5. After landing, finish the flight and check remaining uploads. Verify the operation in Spotlight with an account that has access.

Do not send simulated flights or development test declarations to the hosted Panama service. Mini 2 automatic missions remain disabled in ordinary builds; this setup changes network reporting only. Broadcast Remote ID is outside this workflow.

## Switching from development or another server

New builds associate each flight with its original server. Before replacing an older saved setup, the app associates existing untagged flights with that previous server once. If their origin is unknown, it blocks upload rather than guessing.

Old flights stay in history and are not replayed to Panama. Restore their original setup to retry their original server. A new plan uses the currently selected server. The app also clears the active selection of a foreign-server flight and stops using another server's cached restriction zones.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| `10.0.2.2`, `localhost` or a private LAN address under UTM server | This is development setup. Import the Panama profile. |
| Setup refused | Ask the administrator to complete the required identity, credential and height fields. Do not paste secrets into support messages. |
| Unauthorized / HTTP 401 or 403 | Ask the administrator to check the phone's OAuth application, scopes, audience and revocation status. Signing into the website alone will not repair a device credential. |
| Flight belongs to another server / no verified server association | Keep the record. Restore the original setup to sync; create a new plan for Panama. Unknown-origin legacy records require review and remain blocked. |
| No declaration | Open Plan, review the operation and use Save and declare. Merely opening the app does not create one. |
| Pending samples or declaration window closed | Restore internet access and check the operation window. Keep/export the local record; do not create duplicate declarations to force old telemetry through. |
| No pilot or aircraft choices | Ask the administrator for the approved roster and registration entries in the setup. |
| No zones after switching servers | Download the selected server's zones from Device setup. Do not rely on zones cached for development. |
