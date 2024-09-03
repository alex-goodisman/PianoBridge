# PianoBridge
Combination Audio Interface and Discord Bot Client to run on your phone- play an electric piano over Discord

## What is this for?
If you have an electric piano keyboard (or any other audio source that's compatible with your phone), you've probably wondered if if you could somehow stream that audio to your friends and family over the Internet. Specifically, you may have wanted to join a Discord call and pretend to be a virtual saloon entertainer for your friends while listening to the call.

However, the combined forces of Discord's mobile app changing all the time, mobile audio drivers generally being poorly documented and bad, and hardware being questionable have probably made you give up on this goal.

Well, you don't have to! For the low, low price of a TRS-TRRS 3.5mm headphone/mic splitter cable, a 3.5mm plug-to-plug adapter, and this software, the  dream is alive once more!

## How do I use it?
- First you will need to wire everything up correctly. I used a 3.5mm TRRS headphone/mic splitter (one plug to two jacks) that connects to my phone's headphone jack and gives a mic port and a headphone port. The mic port connects to a plug-to-plug adapter, the other end of which goes into the piano. I found that the splitter has to go into the phone before the "mic" (piano) goes into the splitter, or the phone won't register it as an audio source.
- Then you need to create a discord Bot with voice permissions, and acquire a token for it, then get it added to your servers.
- Finally, you can plug the phone into the piano, run the app, connect to the server, and play away for your friends to complain about in the voice channel. You will be able to hear yourself play through the headphone port.

### Can I talk to other people?
Other participants in the call will see the Bot user join when you press the Start button. They will hear your playing coming from the bot. You will hear them talking, which will be live-mixed on top of the sound of your playing (only for you). If you want to talk, you can press the Toggle Button. This will stop transmitting the piano (both to Discord and locally) and instead transmit the phone's ambient microphone. This way when someone insults your playing, you can stop and reply in kind, then go back to playing. Sadly, Android provides a hard limit of one (1) audio input stream at a time, so you can't chat while playing. But being able to pause and talk should suffice.

### How is the latency?
Latency sending the music to discord and retrieving the voice call data is up to the implementation and your network connection, which is to say it's bad. However, the loopback circuit so that you can hear yourself play is extremely low-latency, so you should still be able to stream on a bad connection, you'll just be a few seconds in the future.

## How does it work?
The loopback circuit is run entirely in the native layer through JNI, and uses Google's Oboe wrapper around AAudio to stream the mic to the headphones with extremely low latency. The Discord connection is run in the Kotlin layer, using the Kord coroutine library for Discord voice connections. Audio samples are queued in the native layer, and every time Kord provides or requests a sample, it retrieves the next data from the queue over the JNI bridge. Audio conversions use the Concentus codec for the Opus audio format that Discord expects.

## Future things
When you're in talking mode, it currently still runs the loopback circuit so you hear your own voice. This is fine, since the latency is low enough it just sounds like being in an echoey room. But it could easily by disabled by just passing an extra flag to the audio input stream wrapper over JNI, so it should probably do that.

Also this is only guaranteed to work on my phone, with my piano, on this version of Android, and on current versions of the Discord API, so it's very likely to break for you. If you have a different setup, or you use iOS, then you'll have to do what I did and implement it yourself.
