# HMS error text

One small JSON file per HMS module (the first four digits of the code). The printer driver fetches a file only when the printer reports a code it hasn't seen, and keeps just the matching line on the hub. Nothing here is sent to Bambu.

Each entry is `code: [text, wiki, h2 wiki]`. A wiki value is the Bambu Lab Wiki model folder (`x1`, `h2`), `folder|-` when that page uses dashes, or a full path. AMS units B to H (07xx, 18xx, 12xx) share unit A's file and line; the driver swaps in the unit letter.

`make_tables.py` rebuilds these files from ha-bambulab's `scripts/hms_error_text` folder:
`python3 make_tables.py <ha-bambulab>/scripts/hms_error_text hms`

## Credit

The English HMS text and Wiki links come from [ha-bambulab](https://github.com/greghesp/ha-bambulab) (commit 242be3e, November 2026), used under its MIT license:

    MIT License
    
    Copyright (c) 2023 ha-bambulab contributors
    
    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:
    
    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.
    
    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
