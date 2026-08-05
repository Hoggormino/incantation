# Draft replies — GitHub issues #1, #2, #3

Not posted. Review and edit before sending. Delete this file once they're up.

---

## Issue #1 — "Mod asking for client gui on server (server crashes as a result)" (@Shadower007, 0.9.0)

> Fixed in 0.9.4. Sorry this sat unanswered for so long, and sorry it took a second
> report to get dealt with properly.
>
> The cause was the config-screen registration in the mod's constructor:
>
> ```java
> container.registerExtensionPoint(IConfigScreenFactory.class,
>     (c, parent) -> new VoiceSpellsConfigScreen(parent));
> ```
>
> That lambda compiles into a synthetic method whose signature names `Screen`, and NeoForge
> resolves the types a method references when the method is *prepared* — before any code
> inside it runs. So the `if (FMLEnvironment.dist == Dist.CLIENT)` wrapping it never got a
> chance to help: the class load happened during construction, which is why the crash landed
> in `constructMods`. A runtime check simply cannot guard something that happens at method
> preparation time; the reference has to move into a class the server never touches, which is
> what 0.9.4 does.
>
> You were right that there was no workaround — the mod is needed on both sides, so there was
> nothing to disable. Thanks for the clear report, including the environment details; the
> stack trace made this unambiguous once someone actually looked at it.

---

## Issue #2 — "Dedicated server crash - Incantation loads client GUI class Screen for 0.9.3" (@Shadower007)

> Fixed in 0.9.4, and thank you for re-reporting it — you shouldn't have had to.
>
> This is the same root cause as #1. The 0.9.1 fix was real but incomplete: it moved the SVC
> plugin, the spell index and the theme cache off the client-only class chain, and missed the
> config-screen registration in the mod constructor, which is the one that was actually
> crashing. So 0.9.1 looked fixed for the paths that were audited and stayed broken for the
> one that wasn't.
>
> The reason it went unnoticed twice is more useful than the fix itself: the project had no
> dedicated-server run configuration. Every release was tested on a client, where this code
> path is fine by definition. `./gradlew runServer` now exists and reproduces the crash on
> the old build in about 20 seconds, and every release from 0.9.4 onward is checked against
> it. That's the part that stops a third occurrence.
>
> Closing #1 and #2 together once 0.9.5 is published to CurseForge and Modrinth.

---

## Issue #3 — "Cast Error:InvocationTargetException" (@Tubeess)

> This one isn't an Incantation bug, though it absolutely looks like one and I don't blame
> you for filing it here. It's a conflict between **Iron's Restrictions** and Iron's Spells.
>
> Another user, Kogyuu, hit the identical error and traced it themselves — disabling Iron's
> Restrictions resolved it completely. I've since confirmed the mechanism by decompiling
> `irons_restrictions-1.21.1-5.2.0.jar`: it ships a mixin, `AbstractSpellMixin`, that injects
> into `AbstractSpell.canBeCastedBy(...)` and reads a NeoForge data attachment
> (`RARITY_DATA`) along with your Curios slots, calling
> `ISpellContainer.get(stack).getAllSpells()`. When that chain fails it throws, and because
> every cast Incantation performs goes through reflection, the exception arrives wrapped —
> which is why you got the useless `InvocationTargetException` instead of anything naming the
> actual culprit. That also explains why downgrading Iron's Spells and Incantation changed
> nothing: neither was the source.
>
> **Workaround today:** remove Iron's Restrictions, or check whether its learning/rarity
> options can be configured so the spells you're casting aren't gated by it.
>
> **What 0.9.5 changes:** the error is unwrapped to its real cause, and the mod now walks the
> stack trace to identify which mod the failure actually came from and names it. Instead of
> `Cast Error: InvocationTargetException` you'll get `Cast blocked by Iron's Restrictions —
> mod conflict`, with the full detail in the log. That doesn't fix the conflict — it isn't
> mine to fix — but it means nobody else loses a week to this.
>
> Worth reporting upstream to Iron's Restrictions as well; a null check in that mixin would
> resolve it properly.
