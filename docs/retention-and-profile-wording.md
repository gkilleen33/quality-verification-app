# Draft in-app wording: profile collection and deletion

Draft for review, not final copy. Two places the app has to say something it currently
does not.

## 1. At registration, above the profile form

> **A few details before we start**
>
> Your name is how we address you in reports you share. If you tell us your business, we
> can group your assessments together and — later — help other buyers find workshops with
> a good record.
>
> If you are at your business right now, you can save its location. That is optional, you
> can skip it, and you can register without it. We use it only to place your business on a
> map of workshops; we do not track where you are afterwards.

On the location step specifically, next to the button:

> **Save this location** · Only tap this if you are at the business now.

## 2. When deleting an assessment

The current behaviour deletes locally and is silent about the server. With write-through
and 7-day retention it has to say so:

> **Delete this report?**
>
> It will be removed from your phone straight away. We keep a copy on our server for
> 7 days to check the quality of our assessments, then it is deleted for good.

Not "deleted forever", which would be untrue for a week.

## 2b. That people, not just systems, look at assessments

Added 1 Sep 2026, once the admin portal was scoped. Staff can open any assessment and see
its photos, so saying only that we "check the quality of our assessments" understates it —
that phrasing sounds like automated monitoring. One sentence fixes it, and it belongs
wherever the retention notice appears:

> Researchers working on Kagua can open an assessment, including its photos, to check how
> accurate our advice was.

"Researchers working on Kagua", not "we" — it says a person is involved, which is the part
somebody would want to know. This is cheap to say now and awkward to add after somebody
has asked how their photographs were used.

## 3. Why the wording matters more than usual here

Kenya's Data Protection Act 2019 and Uganda's Data Protection and Privacy Act 2019 both
give a data subject the right to erasure. A 7-day operational retention window is a normal
and defensible thing to hold; an *undisclosed* one is the version that causes problems. The
cost of saying it plainly is two sentences.

Note the retention window covers **assessments the customer deleted**. It is not an account
retention policy — profile rows, including a business location, persist until the account
itself is deleted, and that is a separate decision we have not made.

## 4. Translation

The English above is mine and ready for review. **The Swahili is deliberately not drafted
yet.** Every other string in the app has an unreviewed Swahili version on the grounds that
it beats English — that reasoning does not hold for a retention notice, where a
mistranslation misstates what we do with somebody's photographs. These four strings should
go to the same native speaker who reviews `ReportLabels`, and should ship in English until
then rather than in my Swahili.
